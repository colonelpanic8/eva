export const tools = [
  {
    type: "function",
    name: "eva_counter_get",
    description:
      "Read EVA's displayed in-memory counter. Use for every request for its current value.",
    inputSchema: { type: "object", properties: {}, additionalProperties: false },
  },
  {
    type: "function",
    name: "eva_counter_set",
    description:
      "Set EVA's displayed in-memory counter to an integer from -1000 to 1000. Report the returned value.",
    inputSchema: {
      type: "object",
      properties: { value: { type: "integer", minimum: -1000, maximum: 1000 } },
      required: ["value"],
      additionalProperties: false,
    },
  },
];

export type ToolResult = { requestId: string; success: boolean; value?: number; error?: string };

export class Dispatcher {
  value = 0;
  private receipts = new Map<string, { fingerprint: string; result: ToolResult }>();

  execute(requestId: string, name: string, args: unknown): ToolResult {
    if (!requestId || requestId.length > 256) throw new Error("Invalid request ID");
    const fingerprint = JSON.stringify([name, args]);
    const prior = this.receipts.get(requestId);
    if (prior) {
      return prior.fingerprint === fingerprint
        ? prior.result
        : { requestId, success: false, error: "Request ID reused with different arguments" };
    }
    if (this.receipts.size >= 500) {
      return { requestId, success: false, error: "Session action limit reached; reconnect" };
    }
    const result: ToolResult = { requestId, success: false };
    if (!args || typeof args !== "object" || Array.isArray(args)) {
      result.error = "Arguments must be an object";
    } else if (name === "eva_counter_get" && Object.keys(args).length === 0) {
      result.success = true;
      result.value = this.value;
    } else if (name === "eva_counter_set" && Object.keys(args).join() === "value") {
      const value = (args as { value: unknown }).value;
      if (typeof value === "number" && Number.isInteger(value) && Math.abs(value) <= 1000) {
        this.value = value;
        result.value = value;
        result.success = true;
      } else result.error = "value must be an integer from -1000 to 1000";
    } else result.error = "Unknown tool or unexpected arguments";
    this.receipts.set(requestId, { fingerprint, result });
    return result;
  }
}
