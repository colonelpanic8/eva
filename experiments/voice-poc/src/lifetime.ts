export class Lifetime {
  closed = false;
  private cleanup: (() => void)[] = [];
  add(dispose: () => void): void {
    if (this.closed) dispose();
    else this.cleanup.push(dispose);
  }
  close(): void {
    if (this.closed) return;
    this.closed = true;
    for (const dispose of this.cleanup.splice(0).reverse()) {
      try {
        dispose();
      } catch {
        /* Continue releasing the remaining resources. */
      }
    }
  }
  assertOpen(): void {
    if (this.closed) throw new Error("Connection canceled");
  }
}

export function waitForState(
  target: EventTarget,
  event: string,
  ready: () => boolean,
  lifetime: Lifetime,
  timeoutMs: number,
): Promise<void> {
  return new Promise((resolve, reject) => {
    let done = false;
    const finish = (error?: Error) => {
      if (done) return;
      done = true;
      clearTimeout(timer);
      target.removeEventListener(event, check);
      if (error) reject(error);
      else resolve();
    };
    const check = () => {
      if (ready()) finish();
    };
    const timer = setTimeout(() => finish(new Error(`Timed out waiting for ${event}`)), timeoutMs);
    target.addEventListener(event, check);
    lifetime.add(() => finish(new Error("Connection canceled")));
    check();
  });
}
