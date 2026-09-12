import type { ChildProcessWithoutNullStreams } from "node:child_process";
import { createInterface } from "node:readline";

export type RecordValue = Record<string, unknown>;
export function record(value: unknown): RecordValue {
  return value && typeof value === "object" && !Array.isArray(value) ? (value as RecordValue) : {};
}

export function safeError(error: unknown): string {
  const message = error instanceof Error ? error.message : String(error);
  return message
    .replace(/Bearer\s+\S+/gi, "Bearer [redacted]")
    .replace(/\b(?:sk-[\w-]+|eyJ[\w.-]{20,})\b/g, "[redacted]")
    .replace(/https?:\/\/[^\s"<>]+/g, "[url omitted]")
    .replace(/[\w.+-]+@[\w.-]+\.[a-z]{2,}/gi, "[email omitted]")
    .slice(0, 800);
}

export class Rpc {
  private nextId = 1;
  private pending = new Map<
    number,
    { resolve: (value: unknown) => void; reject: (error: Error) => void; timer: NodeJS.Timeout }
  >();
  private closed = false;
  onNotification: (method: string, params: RecordValue) => void = () => {};
  onRequest: (method: string, params: RecordValue) => unknown = () => {
    throw new Error("Unsupported request");
  };
  onClose: () => void = () => {};

  constructor(private child: ChildProcessWithoutNullStreams) {
    const lines = createInterface({ input: child.stdout });
    lines.on("line", (line) => {
      try {
        this.receive(JSON.parse(line));
      } catch {
        this.dispose();
      }
    });
    child.stderr.on("data", () => {}); // Provider stderr may contain account or transport data.
    child.on("error", () => this.dispose());
    child.on("exit", () => {
      lines.close();
      this.dispose();
    });
    child.stdin.on("error", () => this.dispose());
  }

  receive(value: unknown): void {
    const msg = record(value);
    if (typeof msg.method === "string") {
      if (msg.id !== undefined) {
        Promise.resolve()
          .then(() => this.onRequest(msg.method as string, record(msg.params)))
          .then((result) => this.send({ id: msg.id, result }))
          .catch(() =>
            this.send({ id: msg.id, error: { code: -32601, message: "Request denied by EVA" } }),
          );
      } else this.onNotification(msg.method, record(msg.params));
    } else if (typeof msg.id === "number") {
      const pending = this.pending.get(msg.id);
      if (!pending) return;
      clearTimeout(pending.timer);
      this.pending.delete(msg.id);
      if (msg.error)
        pending.reject(new Error(safeError(record(msg.error).message ?? "RPC failed")));
      else pending.resolve(msg.result);
    }
  }

  send(message: unknown): void {
    if (!this.closed) this.child.stdin.write(`${JSON.stringify(message)}\n`);
  }

  request(method: string, params: unknown, timeoutMs = 30_000): Promise<unknown> {
    if (this.closed) return Promise.reject(new Error("Codex process closed"));
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`${method} timed out after ${timeoutMs}ms`));
      }, timeoutMs);
      this.pending.set(id, { resolve, reject, timer });
      this.send({ id, method, params });
    });
  }

  dispose(): void {
    if (this.closed) return;
    this.closed = true;
    for (const pending of this.pending.values()) {
      clearTimeout(pending.timer);
      pending.reject(new Error("Codex process closed"));
    }
    this.pending.clear();
    this.child.kill("SIGTERM");
    const kill = setTimeout(() => {
      this.child.kill("SIGKILL");
    }, 2000);
    kill.unref();
    this.child.once("exit", () => clearTimeout(kill));
    this.onClose();
  }
}
