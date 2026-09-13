package com.colonelpanic.eva.deviceprobe;

import android.annotation.SuppressLint;
import android.app.UiAutomation;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONArray;
import org.json.JSONObject;

/** Shell-side proof of concept; only the bundled fixture may receive input. */
public class DeviceProbeService extends IDeviceProbe.Stub {
    private static final String FIXTURE = "com.colonelpanic.eva.devicefixture";
    private static final String SAMPLE = "héllo 日本語 🙂";

    @Override
    public void destroy() {
        System.exit(0);
    }

    @Override
    public String ping() {
        return "uid=" + Process.myUid() + ",pid=" + Process.myPid();
    }

    @SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
    @Override
    public synchronized String runProbe(String operation, ParcelFileDescriptor image) {
        JSONObject result = new JSONObject();
        HandlerThread thread = new HandlerThread("eva-ui-automation");
        thread.start();
        UiAutomation automation = null;
        long started = SystemClock.elapsedRealtime();
        try (ParcelFileDescriptor output = image) {
            result.put("uid", Process.myUid());
            result.put("pid", Process.myPid());
            result.put("sdk", android.os.Build.VERSION.SDK_INT);
            if (Process.myUid() != 2000) throw new IllegalStateException("Expected shell, not root/app");
            Class<?> connectionType = Class.forName("android.app.IUiAutomationConnection");
            Object connection = Class.forName("android.app.UiAutomationConnection").getConstructor().newInstance();
            automation = UiAutomation.class.getConstructor(Looper.class, connectionType)
                    .newInstance(thread.getLooper(), connection);
            UiAutomation.class.getMethod("connect", int.class)
                    .invoke(automation, UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            result.put("connected", true);
            long rootDeadline = SystemClock.elapsedRealtime() + 4000;
            while (automation.getRootInActiveWindow() == null && SystemClock.elapsedRealtime() < rootDeadline) {
                SystemClock.sleep(100);
            }
            if (automation.getRootInActiveWindow() == null) throw new IllegalStateException("No active UI root after 4 seconds");
            result.put("before", observe(automation));
            if ("exercise".equals(operation)) {
                AccessibilityNodeInfo root = fixtureRoot(automation);
                AccessibilityNodeInfo input = find(root, "probe-input");
                if (input == null) throw new IllegalStateException("Fixture input missing");
                Bundle arguments = new Bundle();
                arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, SAMPLE);
                result.put("setTextAcknowledged", input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments));
                SystemClock.sleep(250);
                root = fixtureRoot(automation);
                AccessibilityNodeInfo button = find(root, "probe-increment");
                if (button == null || !button.isVisibleToUser()) throw new IllegalStateException("Fixture button missing");
                Rect bounds = new Rect();
                button.getBoundsInScreen(bounds);
                long downTime = SystemClock.uptimeMillis();
                MotionEvent down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN,
                        bounds.centerX(), bounds.centerY(), 0);
                MotionEvent up = MotionEvent.obtain(downTime, downTime + 80, MotionEvent.ACTION_UP,
                        bounds.centerX(), bounds.centerY(), 0);
                down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                try {
                    result.put("downAcknowledged", automation.injectInputEvent(down, true));
                    result.put("upAcknowledged", automation.injectInputEvent(up, true));
                } finally {
                    down.recycle();
                    up.recycle();
                }
                SystemClock.sleep(350);
                AccessibilityNodeInfo after = fixtureRoot(automation);
                AccessibilityNodeInfo field = find(after, "probe-input");
                AccessibilityNodeInfo counter = find(after, "probe-counter");
                result.put("unicodeVerified", field != null && SAMPLE.equals(String.valueOf(field.getText())));
                result.put("tapVerified", counter != null && "Count: 1".equals(String.valueOf(counter.getText())));
            }
            result.put("after", observe(automation));
            Bitmap bitmap = automation.takeScreenshot();
            if (bitmap == null) throw new IllegalStateException("Screenshot returned null");
            try (ParcelFileDescriptor.AutoCloseOutputStream stream = new ParcelFileDescriptor.AutoCloseOutputStream(output)) {
                result.put("imageWidth", bitmap.getWidth());
                result.put("imageHeight", bitmap.getHeight());
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    throw new IllegalStateException("PNG encoding failed");
                }
            } finally {
                bitmap.recycle();
            }
            result.put("ok", !"exercise".equals(operation)
                    || (result.optBoolean("unicodeVerified") && result.optBoolean("tapVerified")));
        } catch (Exception error) {
            try {
                result.put("ok", false);
                result.put("error", android.util.Log.getStackTraceString(error));
            } catch (org.json.JSONException ignored) {
                throw new IllegalStateException(ignored);
            }
        } finally {
            if (automation != null) {
                try {
                    UiAutomation.class.getMethod("disconnect").invoke(automation);
                } catch (Exception ignored) {
                    // Failed connects have nothing to disconnect.
                }
            }
            thread.quitSafely();
        }
        try {
            result.put("elapsedMs", SystemClock.elapsedRealtime() - started);
        } catch (org.json.JSONException error) {
            throw new IllegalStateException(error);
        }
        return result.toString();
    }

    private static AccessibilityNodeInfo fixtureRoot(UiAutomation automation) {
        AccessibilityNodeInfo root = automation.getRootInActiveWindow();
        if (root == null || !FIXTURE.equals(String.valueOf(root.getPackageName()))) {
            throw new IllegalStateException("Refusing input outside the test fixture");
        }
        return root;
    }

    private static AccessibilityNodeInfo find(AccessibilityNodeInfo node, String description) {
        if (node == null) return null;
        if (description.equals(String.valueOf(node.getContentDescription()))) return node;
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo match = find(node.getChild(index), description);
            if (match != null) return match;
        }
        return null;
    }

    private static JSONObject observe(UiAutomation automation) throws org.json.JSONException {
        JSONObject observation = new JSONObject();
        JSONArray nodes = new JSONArray();
        AccessibilityNodeInfo root = automation.getRootInActiveWindow();
        observation.put("rootPackage", root == null ? JSONObject.NULL : root.getPackageName());
        collect(root, nodes, 0);
        observation.put("nodes", nodes);
        return observation;
    }

    private static void collect(AccessibilityNodeInfo node, JSONArray nodes, int depth) throws org.json.JSONException {
        if (node == null || depth > 40 || nodes.length() >= 150) return;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        JSONObject value = new JSONObject();
        value.put("text", node.getText());
        value.put("description", node.getContentDescription());
        value.put("bounds", bounds.flattenToString());
        value.put("class", node.getClassName());
        value.put("clickable", node.isClickable());
        nodes.put(value);
        for (int index = 0; index < node.getChildCount(); index++) collect(node.getChild(index), nodes, depth + 1);
    }
}
