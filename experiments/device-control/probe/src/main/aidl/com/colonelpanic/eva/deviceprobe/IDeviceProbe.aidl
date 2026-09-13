package com.colonelpanic.eva.deviceprobe;
import android.os.ParcelFileDescriptor;
interface IDeviceProbe {
    void destroy() = 16777114;
    String ping() = 1;
    String runProbe(String operation, in ParcelFileDescriptor image) = 2;
}
