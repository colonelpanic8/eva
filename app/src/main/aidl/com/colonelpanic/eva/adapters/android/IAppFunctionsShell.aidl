package com.colonelpanic.eva.adapters.android;

import android.os.Bundle;

interface IAppFunctionsShell {
    Bundle execute(in String[] args, long timeoutMillis) = 1;
    void destroy() = 16777114;
}
