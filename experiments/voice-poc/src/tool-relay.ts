import { randomUUID } from "node:crypto";
import { type RecordValue, record } from "./rpc.ts";

export type RelayResult = RecordValue & { success: boolean };
type Receipt = {
  fingerprint: string;
  inputId: string;
  providerTurnId: string;
  result: Promise<RelayResult>;
  finish: (result: RelayResult) => void;
};

export function deviceTools(value: unknown): RecordValue[] {
  if (!Array.isArray(value) || value.length < 1 || value.length > 32)
    throw new Error("Catalog must contain 1–32 tools");
  const names = new Set<string>();
  return value.map((value) => {
    const tool = record(value);
    if (
      typeof tool.name !== "string" ||
      !/^[a-zA-Z][a-zA-Z0-9_]{0,63}$/.test(tool.name) ||
      names.has(tool.name) ||
      typeof tool.description !== "string" ||
      tool.description.length > 2000 ||
      record(tool.inputSchema).type !== "object"
    )
      throw new Error("Invalid or duplicate catalog tool");
    names.add(tool.name);
    return {
      type: "function",
      name: tool.name,
      description: tool.description,
      inputSchema: tool.inputSchema,
    };
  });
}

export class ToolRelay {
  readonly sessionId = randomUUID();
  private inputId?: string;
  private providerTurnId?: string;
  private closed = false;
  private receipts = new Map<string, Receipt>();
  private pending = new Set<string>();
  private inputIds = new Set<string>();
  private actionClaimed = false;

  constructor(
    private tools: RecordValue[],
    private emit: (event: RecordValue) => void,
    private timeoutMs = 30_000,
    readonly catalogRevision = "test-catalog",
  ) {}

  beginInput(inputId: string): void {
    if (
      this.closed ||
      this.inputId ||
      !inputId ||
      inputId.length > 128 ||
      this.inputIds.has(inputId)
    )
      throw new Error("Input is invalid, duplicated, or another turn is active");
    if (this.inputIds.size >= 100) throw new Error("Input limit reached");
    this.inputIds.add(inputId);
    this.inputId = inputId;
    this.providerTurnId = undefined;
    this.actionClaimed = false;
  }

  startGeneration(providerTurnId: string): void {
    if (!this.inputId || this.providerTurnId || !providerTurnId)
      throw new Error("Unexpected response generation");
    this.providerTurnId = providerTurnId;
  }

  completeInput(): string | undefined {
    if (this.pending.size) throw new Error("Provider completed with an unresolved tool call");
    const id = this.inputId;
    this.inputId = undefined;
    this.providerTurnId = undefined;
    return id;
  }

  execute = (callId: string, tool: string, args: unknown): Promise<RelayResult> => {
    if (this.closed || !this.inputId || !this.providerTurnId)
      return Promise.reject(new Error("No active input or generation"));
    if (!callId || callId.length > 128 || !this.tools.some((entry) => entry.name === tool))
      return Promise.reject(new Error("Unknown tool or invalid call ID"));
    const fingerprint = JSON.stringify([tool, args]);
    const prior = this.receipts.get(callId);
    if (prior) {
      if (prior.fingerprint !== fingerprint)
        return Promise.reject(new Error("Conflicting tool call ID"));
      return prior.result;
    }
    if (this.receipts.size >= 100) return Promise.reject(new Error("Tool limit reached"));
    if (this.actionClaimed) {
      const result = Promise.resolve({
        success: false,
        status: "NOT_EXECUTED",
        message:
          "This connection permits one phone action per user request. Ask the user for the next action.",
      });
      this.receipts.set(callId, {
        fingerprint,
        inputId: this.inputId,
        providerTurnId: this.providerTurnId,
        result,
        finish: () => {},
      });
      return result;
    }
    const inputId = this.inputId;
    const providerTurnId = this.providerTurnId;
    this.actionClaimed = true;
    let finish!: (value: RelayResult) => void;
    const result = new Promise<RelayResult>((resolve) => {
      finish = resolve;
    });
    const timer = setTimeout(() => {
      receipt.finish({
        success: false,
        status: "UNKNOWN",
        message: "Phone result timed out. Do not retry this action.",
      });
      this.close();
      this.emit({
        kind: "error",
        message: "Phone result timed out. Check action history before reconnecting.",
      });
    }, this.timeoutMs);
    const receipt: Receipt = {
      fingerprint,
      inputId,
      providerTurnId,
      result,
      finish: (value) => {
        clearTimeout(timer);
        this.pending.delete(callId);
        finish(value);
      },
    };
    this.receipts.set(callId, receipt);
    this.pending.add(callId);
    this.emit({
      kind: "tool-call",
      sessionId: this.sessionId,
      inputId,
      generationId: inputId,
      providerTurnId,
      catalogRevision: this.catalogRevision,
      callId,
      tool,
      arguments: args,
    });
    return result;
  };

  accept(value: RecordValue): void {
    if (
      this.closed ||
      value.sessionId !== this.sessionId ||
      value.catalogRevision !== this.catalogRevision ||
      typeof value.callId !== "string"
    )
      throw new Error("Stale tool result");
    const receipt = this.receipts.get(value.callId);
    if (
      !receipt ||
      receipt.inputId !== value.inputId ||
      value.generationId !== receipt.inputId ||
      receipt.providerTurnId !== value.providerTurnId ||
      !this.pending.has(value.callId)
    )
      throw new Error("Unexpected tool result");
    const result = record(value.result);
    if (
      ![
        "COMPLETED",
        "ACCEPTED",
        "HANDED_OFF",
        "NOT_EXECUTED",
        "FAILED",
        "CANCELED",
        "UNKNOWN",
        "REQUIRES_RESOLUTION",
      ].includes(String(result.status)) ||
      typeof result.message !== "string" ||
      result.message.length > 2000
    )
      throw new Error("Invalid phone outcome");
    receipt.finish({
      status: result.status,
      message: result.message,
      ...Object.fromEntries(
        ["data", "evidence", "requirement", "invocationId", "reason"]
          .filter((key) => key in result)
          .map((key) => [key, result[key]]),
      ),
      success: ["COMPLETED", "ACCEPTED", "HANDED_OFF"].includes(String(result.status)),
    });
  }

  close(): void {
    this.closed = true;
    for (const id of [...this.pending])
      this.receipts.get(id)?.finish({
        success: false,
        status: "UNKNOWN",
        message: "Phone connection closed before its result arrived. Do not retry.",
      });
  }
}
