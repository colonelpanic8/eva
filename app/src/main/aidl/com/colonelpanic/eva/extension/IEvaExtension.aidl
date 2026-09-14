package com.colonelpanic.eva.extension;

import com.colonelpanic.eva.extension.IEvaExtensionCallback;

oneway interface IEvaExtension {
    void describe(String requestId, long deadlineElapsedRealtimeMillis,
                  IEvaExtensionCallback callback);
    void execute(String invocationId, String expectedRevision,
                 String capability, String argumentsJson,
                 long deadlineElapsedRealtimeMillis,
                 IEvaExtensionCallback callback);
}
