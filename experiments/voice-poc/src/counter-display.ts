import type { ToolResult } from "./dispatcher.ts";

export class CounterDisplay {
  value = 0;
  private seen = new Set<string>();

  apply(receipt: ToolResult): number {
    if (!this.seen.has(receipt.requestId)) {
      this.seen.add(receipt.requestId);
      if (receipt.success && receipt.value !== undefined) this.value = receipt.value;
    }
    return this.value;
  }
}
