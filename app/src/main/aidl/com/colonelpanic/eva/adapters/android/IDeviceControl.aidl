package com.colonelpanic.eva.adapters.android;

/**
 * Screen state and fixed input primitives, executed by a Shizuku user service running as shell.
 * Payloads are bounded JSON; commands are a fixed set of named operations, never shell text.
 */
interface IDeviceControl {
    void destroy() = 16777114;
    // Transactions 1 and 2 carried the retired element observe/act protocol; do not reuse them.
    /** Portal-shaped screen state for the device-task backend. */
    String state(long timeoutMillis) = 3;
    /** One Portal-shaped primitive command; returns {"ok":…, "detail":…}. */
    String command(String method, String params, long timeoutMillis) = 4;
    /** PNG bytes, streamed because a screen capture can exceed the Binder transaction limit. */
    ParcelFileDescriptor screenshot(long timeoutMillis) = 5;
}
