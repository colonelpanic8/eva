package com.colonelpanic.eva.deviceprobe;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import rikka.shizuku.Shizuku;

@SuppressLint("SetTextI18n")
public class ProbeActivity extends Activity {
    private TextView status;
    private IDeviceProbe helper;
    private boolean busy;
    private String pending;
    private final Shizuku.UserServiceArgs serviceArgs = new Shizuku.UserServiceArgs(
            new ComponentName("com.colonelpanic.eva.deviceprobe", DeviceProbeService.class.getName()))
            .daemon(false).processNameSuffix("device_probe").tag("eva-device-probe").version(1);
    private final Shizuku.OnBinderReceivedListener received = this::runPending;
    private final Shizuku.OnBinderDeadListener died = () -> {
        helper = null;
        status.setText("Shizuku disconnected");
        save("availability.json", "{\"available\":false}");
    };
    private final Shizuku.OnRequestPermissionResultListener permission = (code, grant) -> {
        if (grant == PackageManager.PERMISSION_GRANTED) runPending();
        else {
            status.setText("Permission denied");
            save("result.json", "{\"ok\":false,\"permissionDenied\":true}");
        }
    };
    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            helper = IDeviceProbe.Stub.asInterface(binder);
            runPending();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            helper = null;
        }
    };

    @SuppressLint("SetTextI18n")
    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 140, 48, 48);
        status = new TextView(this);
        status.setText("Experimental Shizuku device-control probe");
        layout.addView(status);
        for (String op : new String[]{"ping", "observe", "exercise"}) {
            Button button = new Button(this);
            button.setText(op);
            button.setOnClickListener(v -> request(op));
            layout.addView(button);
        }
        setContentView(layout);
        Shizuku.addRequestPermissionResultListener(permission);
        Shizuku.addBinderDeadListener(died);
        Shizuku.addBinderReceivedListenerSticky(received);
        acceptIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        acceptIntent(intent);
    }

    private void acceptIntent(Intent intent) {
        String op = intent.getStringExtra("operation");
        if (op != null) request(op);
    }

    private void request(String op) {
        if (busy) return;
        if (!op.equals("ping") && !op.equals("observe") && !op.equals("exercise") && !op.equals("secure")) return;
        pending = op;
        save("result.json", "{\"pending\":true}");
        runPending();
    }

    private void runPending() {
        if (pending == null || busy) return;
        if (!Shizuku.pingBinder()) {
            status.setText("Start Shizuku first");
            save("result.json", "{\"ok\":false,\"shizukuUnavailable\":true}");
            return;
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(1);
            return;
        }
        if (helper == null) {
            Shizuku.bindUserService(serviceArgs, connection);
            return;
        }
        final String op = pending;
        final IDeviceProbe service = helper;
        pending = null;
        busy = true;
        if (!op.equals("ping")) {
            Intent target = new Intent().setComponent(new ComponentName(
                    "com.colonelpanic.eva.devicefixture", "com.colonelpanic.eva.devicefixture.FixtureActivity"));
            target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            target.putExtra("secure", op.equals("secure"));
            startActivity(target);
        }
        new Thread(() -> {
            String output;
            try {
                if (op.equals("ping")) {
                    output = new JSONObject().put("ok", true).put("identity", service.ping())
                            .put("clientUid", android.os.Process.myUid()).toString();
                } else {
                    SystemClock.sleep(2000);
                    File image = new File(getFilesDir(), "capture.png");
                    try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(image,
                            ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE | ParcelFileDescriptor.MODE_WRITE_ONLY)) {
                        output = service.runProbe(op, fd);
                    }
                }
            } catch (Exception error) {
                output = "{\"ok\":false,\"error\":" + JSONObject.quote(android.util.Log.getStackTraceString(error)) + "}";
            }
            save("result.json", output);
            final String text = output;
            runOnUiThread(() -> {
                busy = false;
                status.setText(text);
            });
        }, "eva-probe-client").start();
    }

    private void save(String name, String text) {
        try (FileOutputStream stream = openFileOutput(name, MODE_PRIVATE)) {
            stream.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) {
            android.util.Log.e("EvaDeviceProbe", "Cannot save probe result", error);
        }
    }

    @Override
    public void onDestroy() {
        Shizuku.removeBinderReceivedListener(received);
        Shizuku.removeBinderDeadListener(died);
        Shizuku.removeRequestPermissionResultListener(permission);
        super.onDestroy();
    }
}
