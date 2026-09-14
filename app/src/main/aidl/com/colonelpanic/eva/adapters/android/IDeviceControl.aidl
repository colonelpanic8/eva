package com.colonelpanic.eva.adapters.android;

/**
 * Screen observation and bounded input, executed by a Shizuku user service running as shell.
 * Both payloads are bounded JSON defined by DeviceControlProtocol; no shell command crosses here.
 */
interface IDeviceControl {
    void destroy() = 16777114;
    String observe(long timeoutMillis) = 1;
    String act(String request, long timeoutMillis) = 2;
}
