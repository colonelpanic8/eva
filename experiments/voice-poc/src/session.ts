import { spawn } from "node:child_process";
import { randomUUID } from "node:crypto";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Dispatcher, tools } from "./dispatcher.ts";
import { instructions, profile } from "./profile.ts";
import { type RecordValue, Rpc, record, safeError } from "./rpc.ts";

export type Event = { kind: string; atMs: number; [key: string]: unknown };
export type SessionRuntime = {
  chatOnly?: boolean;
  tools: RecordValue[];
  instructions: string;
  execute: (
    callId: string,
    tool: string,
    args: unknown,
  ) => Promise<RecordValue & { success: boolean }>;
};
export class Session {
  readonly dispatcher = new Dispatcher();
  private rpc?: Rpc;
  private cwd?: string;
  private threadId?: string;
  private startedAt = performance.now();
  private closed = false;
  private starting = false;
  private active = false;
  private setupTimer?: NodeJS.Timeout;
  private lifetime?: NodeJS.Timeout;
  private turns = new Set<string>();
  private backendThreads = new Set<string>();
  private lastInputAt?: number;
  private lastSpeechTranscriptAt?: number;
  private typedTurn?: string;
  private typedStarting = false;
  private completedTurns = new Set<string>();
  private actionCount = 0;
  private realtime = false;
  private activeTurnId?: string;

  constructor(
    private emit: (event: Event) => void,
    private runtime?: SessionRuntime,
  ) {}

  private event(kind: string, data: RecordValue = {}): void {
    this.emit({ kind, atMs: Math.round(performance.now() - this.startedAt), ...data });
  }

  async start(sdp: string | undefined, custom: string): Promise<void> {
    if (this.starting || this.closed) throw new Error("Session already started or closed");
    this.starting = true;
    this.realtime = sdp !== undefined;
    if (this.runtime?.chatOnly && this.runtime.tools.length)
      throw new Error("Voice chat cannot advertise tools");
    const sessionInstructions = this.runtime?.instructions ?? instructions;
    this.startedAt = performance.now();
    this.setupTimer = setTimeout(
      () => this.fail(new Error("Session setup exceeded 60 seconds")),
      60_000,
    );
    this.lifetime = setTimeout(
      () => this.fail(new Error("10-minute experiment limit reached")),
      600_000,
    );
    try {
      this.cwd = await mkdtemp(join(tmpdir(), "eva-voice-"));
      if (this.closed) {
        await rm(this.cwd, { recursive: true, force: true });
        return;
      }
      const env = { ...process.env };
      for (const key of ["OPENAI_API_KEY", "OPENAI_ADMIN_KEY", "CODEX_API_KEY", "OPENAI_BASE_URL"])
        delete env[key];
      const child = spawn(
        process.env.EVA_CODEX_BIN ?? "codex",
        ["app-server", "--enable", "realtime_conversation"],
        {
          cwd: this.cwd,
          env,
          stdio: ["pipe", "pipe", "pipe"],
        },
      );
      const rpc = new Rpc(child);
      this.rpc = rpc;
      rpc.onNotification = (method, params) => this.notification(method, params);
      rpc.onRequest = (method, params) => this.request(method, params);
      rpc.onClose = () => {
        if (!this.closed) this.fail(new Error("Codex process exited"));
      };
      const init = record(
        await rpc.request("initialize", {
          clientInfo: { name: "eva-voice-poc", version: "0.1.0" },
          capabilities: { experimentalApi: true },
        }),
      );
      rpc.send({ method: "initialized", params: {} });
      this.event("client", { userAgent: safeError(init.userAgent ?? "unknown") });
      const account = record(record(await rpc.request("account/read", {})).account);
      if (account.type !== "chatgpt")
        throw new Error("An existing ChatGPT subscription login is required; API auth is refused");
      this.event("auth", { type: "chatgpt", planType: account.planType });
      const config = record(
        record(await rpc.request("config/read", { cwd: this.cwd, includeLayers: false })).config,
      );
      if (!Object.keys(config).length)
        throw new Error("Cannot isolate inherited Codex configuration");
      if (config.model_provider && config.model_provider !== "openai") {
        throw new Error("This subscription experiment requires the OpenAI provider");
      }
      const provider = record(record(config.model_providers).openai);
      if (provider.base_url || provider.env_key || provider.experimental_bearer_token) {
        throw new Error(
          "Custom OpenAI endpoint or credential overrides are not supported by this experiment",
        );
      }
      const mcpServers = Object.fromEntries(
        Object.keys(record(config.mcp_servers)).map((name) => [name, { enabled: false }]),
      );
      const thread = record(
        await rpc.request("thread/start", {
          model: "gpt-5.6-luna",
          cwd: this.cwd,
          ephemeral: true,
          approvalPolicy: "never",
          sandbox: "read-only",
          baseInstructions:
            "You are EVA. Use only the supplied EVA tools. No shell, files, web, agents, or other integrations.",
          developerInstructions: `${sessionInstructions}\n${custom}\nReturn actual tool results; never invent execution outcomes.`,
          dynamicTools: this.runtime?.tools ?? tools,
          config: {
            ...profile,
            mcp_servers: mcpServers,
            experimental_realtime_ws_backend_prompt: `${sessionInstructions}\n${custom}`,
            model_reasoning_effort: "low",
          },
        }),
      );
      this.threadId = String(record(thread.thread).id ?? "");
      if (!this.threadId) throw new Error("Codex returned no thread ID");
      this.event("thread", {
        model: thread.model,
        inheritedMcpServersDisabled: Object.keys(mcpServers).length,
      });
      if (!this.realtime) {
        this.active = true;
        clearTimeout(this.setupTimer);
        this.event("started", { mode: "text" });
        return;
      }
      await rpc.request("thread/realtime/start", {
        threadId: this.threadId,
        realtimeSessionId: randomUUID(),
        outputModality: "audio",
        version: "v3",
        model: "gpt-live-1-codex",
        transport: { type: "webrtc", sdp },
        includeStartupContext: false,
        prompt: `${sessionInstructions}\n${custom}`,
      });
    } catch (error) {
      this.fail(error);
    }
  }

  private async request(method: string, params: RecordValue): Promise<unknown> {
    if (this.closed) throw new Error("Closed");
    if (
      method === "item/commandExecution/requestApproval" ||
      method === "item/fileChange/requestApproval"
    )
      return { decision: "decline" };
    if (method !== "item/tool/call") throw new Error("Only supplied EVA tools are supported");
    if (this.runtime?.chatOnly) throw new Error("Phone actions are unavailable in voice chat");
    if (typeof params.callId !== "string" || typeof params.tool !== "string")
      throw new Error("Malformed tool request");
    if (
      this.runtime &&
      (!this.active || params.threadId !== this.threadId || params.turnId !== this.activeTurnId)
    )
      throw new Error("Tool call does not belong to the active EVA thread");
    const start = performance.now();
    const result = this.runtime
      ? await this.runtime.execute(params.callId, params.tool, params.arguments)
      : this.dispatcher.execute(params.callId, params.tool, params.arguments);
    this.actionCount++;
    this.event("dispatch", {
      ...result,
      tool: params.tool,
      transport: "codex-stdio-dynamic-tool",
      dispatchMs: performance.now() - start,
      inputToActionMs: this.lastInputAt ? Math.round(performance.now() - this.lastInputAt) : null,
      speechTranscriptToActionMs: this.lastSpeechTranscriptAt
        ? Math.round(performance.now() - this.lastSpeechTranscriptAt)
        : null,
      backendTurns: this.turns.size,
    });
    return {
      success: result.success,
      contentItems: [{ type: "inputText", text: JSON.stringify(result) }],
    };
  }

  private notification(method: string, params: RecordValue): void {
    if (this.closed) return;
    if (method === "turn/started") {
      const id = String(record(params.turn).id ?? "");
      if (
        this.runtime &&
        !this.runtime.chatOnly &&
        (!id || params.threadId !== this.threadId || this.activeTurnId)
      ) {
        this.fail(new Error("Unexpected provider turn"));
        return;
      }
      this.activeTurnId = id;
      if (id) this.turns.add(id);
      this.backendThreads.add(String(params.threadId));
      this.event("backend-turn", {
        providerTurnId: id,
        count: this.turns.size,
        threads: this.backendThreads.size,
      });
      if (this.turns.size > 8) this.fail(new Error("Backend-turn experiment limit reached"));
    } else if (method === "turn/completed") {
      const id = String(record(params.turn).id ?? "");
      if (
        this.runtime &&
        !this.runtime.chatOnly &&
        (id !== this.activeTurnId || params.threadId !== this.threadId)
      ) {
        this.fail(new Error("Unexpected provider turn completion"));
        return;
      }
      this.activeTurnId = undefined;
      this.completedTurns.add(id);
      if (id === this.typedTurn) this.typedTurn = undefined;
      const error = record(record(params.turn).error);
      if (error.message) this.event("turn-error", { message: safeError(error.message) });
      this.event("backend-completed", { providerTurnId: id, status: record(params.turn).status });
    } else if (method === "item/completed" && record(params.item).type === "agentMessage") {
      const text = String(record(params.item).text ?? "");
      this.event("backend-output", {
        providerTurnId: this.activeTurnId,
        text: this.runtime ? text.slice(0, 8000) : safeError(text),
        truncated: this.runtime ? text.length > 8000 : undefined,
      });
    } else if (method === "thread/realtime/sdp" && typeof params.sdp === "string") {
      this.event("answer", { sdp: params.sdp });
    } else if (method === "thread/realtime/started") {
      this.active = true;
      clearTimeout(this.setupTimer);
      this.event("started", { version: params.version ?? "v3", audio: "browser-provider WebRTC" });
    } else if (method === "thread/realtime/transcript/done") {
      if (params.role === "user") this.lastSpeechTranscriptAt = performance.now();
      this.event("transcript", {
        role: params.role,
        text: safeError(params.text),
        inputToTranscriptMs: this.lastInputAt
          ? Math.round(performance.now() - this.lastInputAt)
          : null,
      });
    } else if (method === "thread/realtime/outputAudio/delta") {
      this.event("unexpected-host-audio", {
        bytes: String(record(params.audio).data ?? "").length,
      });
    } else if (method === "thread/realtime/error" || method === "error") {
      this.fail(
        new Error(safeError(params.message ?? record(params.error).message ?? "Provider error")),
      );
    } else if (method === "thread/realtime/closed") {
      this.event("provider-closed", { reason: safeError(params.reason ?? "unknown") });
      void this.close();
    } else if (method.startsWith("thread/realtime/item")) {
      const item = record(params.item);
      this.event("provider-event", { method, itemType: item.type });
    } else if (method === "thread/tokenUsage/updated") {
      const usage = record(params.tokenUsage);
      this.event("usage", { total: record(usage.total).totalTokens });
    }
  }

  async text(text: string, route: "backend" | "realtime-context" = "backend"): Promise<void> {
    if (!this.active || this.closed || !this.rpc)
      throw new Error("Provider session is not connected");
    this.lastInputAt = performance.now();
    this.event("input", { characters: text.length, route });
    if (route === "realtime-context") {
      if (!this.realtime) throw new Error("This session has no realtime connection");
      await this.rpc.request("thread/realtime/appendText", {
        threadId: this.threadId,
        role: "user",
        text,
      });
      return;
    }
    if (this.typedStarting || this.typedTurn)
      throw new Error("A typed backend turn is still running");
    this.typedStarting = true;
    try {
      const response = record(
        await this.rpc.request("turn/start", {
          threadId: this.threadId,
          input: [{ type: "text", text }],
        }),
      );
      const id = String(record(response.turn).id ?? "");
      if (id && !this.completedTurns.has(id)) this.typedTurn = id;
    } finally {
      this.typedStarting = false;
    }
  }

  fail(error: unknown): void {
    if (this.closed) return;
    this.event("error", { message: safeError(error) });
    void this.close();
  }

  async close(): Promise<void> {
    if (this.closed) return;
    this.closed = true;
    clearTimeout(this.setupTimer);
    clearTimeout(this.lifetime);
    if (this.realtime && this.threadId && this.rpc) {
      await this.rpc
        .request("thread/realtime/stop", { threadId: this.threadId }, 2000)
        .catch(() => {});
    }
    this.rpc?.dispose();
    if (this.cwd) await rm(this.cwd, { recursive: true, force: true });
    this.event("closed", {
      backendTurns: this.turns.size,
      actions: this.actionCount,
      value: this.dispatcher.value,
    });
  }
}
