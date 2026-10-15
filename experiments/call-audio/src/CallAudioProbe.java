import android.app.Application;
import android.app.Instrumentation;
import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.os.Looper;
import android.os.Process;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Runs as shell through app_process and exercises Android's call audio interception API: downlink
 * extraction (hear the other party) and uplink injection (speak into the call).
 *
 * <pre>
 * check
 * listen SECONDS OUT.wav
 * speak  IN.wav              (16-bit mono PCM WAV)
 * tone   SECONDS
 * both   SECONDS OUT.wav     (records the downlink while injecting a tone)
 * </pre>
 */
public final class CallAudioProbe {
    private static final int RATE = 16000;
    private static Object activityThread;

    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        installShellApplication();
        AudioManager audio = audioManager();
        String command = args.length > 0 ? args[0] : "check";
        System.out.println("uid=" + Process.myUid() + " mode=" + modeName(audio.getMode()));
        System.out.println("isPstnCallAudioInterceptable=" + call(audio, "isPstnCallAudioInterceptable"));
        switch (command) {
            case "check":
                break;
            case "listen":
                listen(audio, Integer.parseInt(args[1]), new File(args[2]));
                break;
            case "speak":
                speak(audio, readWav(new File(args[1])));
                break;
            case "tone":
                speak(audio, tone(Integer.parseInt(args[1])));
                break;
            case "bridge":
                bridge(audio, Integer.parseInt(args[1]), args.length > 2 ? Integer.parseInt(args[2]) : 24000);
                break;
            case "both": {
                int seconds = Integer.parseInt(args[1]);
                Thread speaker = new Thread(() -> {
                    try {
                        speak(audio, tone(seconds));
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
                speaker.start();
                listen(audio, seconds, new File(args[2]));
                speaker.join();
                break;
            }
            default:
                throw new IllegalArgumentException("unknown command " + command);
        }
        System.exit(0);
    }

    private static void listen(AudioManager audio, int seconds, File out) throws Exception {
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build();
        AudioRecord record = (AudioRecord) call(audio, "getCallDownlinkExtractionAudioRecord", format);
        System.out.println("downlink record state=" + record.getState());
        short[] all = new short[RATE * seconds];
        record.startRecording();
        int read = 0;
        long started = System.nanoTime();
        while (read < all.length) {
            int n = record.read(all, read, Math.min(RATE / 10, all.length - read));
            if (n < 0) throw new IOException("read failed: " + n);
            read += n;
        }
        record.stop();
        record.release();
        for (int s = 0; s < seconds; s++) {
            double sum = 0;
            for (int i = s * RATE; i < (s + 1) * RATE; i++) sum += (double) all[i] * all[i];
            System.out.printf("second %d rms=%.1f%n", s, Math.sqrt(sum / RATE));
        }
        System.out.printf("captured %d samples in %.2fs%n", read, (System.nanoTime() - started) / 1e9);
        writeWav(out, all);
    }

    private static void speak(AudioManager audio, short[] pcm) throws Exception {
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build();
        AudioTrack track = (AudioTrack) call(audio, "getCallUplinkInjectionAudioTrack", format);
        System.out.println("uplink track state=" + track.getState());
        track.play();
        int written = 0;
        while (written < pcm.length) {
            int n = track.write(pcm, written, Math.min(RATE / 10, pcm.length - written));
            if (n < 0) throw new IOException("write failed: " + n);
            written += n;
        }
        Thread.sleep(300);
        track.stop();
        track.release();
        System.out.println("injected " + written + " samples");
    }

    /**
     * Serves one TCP client on 127.0.0.1:[port]: the call downlink streams out and whatever the
     * client sends is injected into the uplink, both as 16-bit little-endian mono PCM at [rate].
     * Ends when the call leaves IN_CALL or the client disconnects.
     */
    private static void bridge(AudioManager audio, int port, int rate) throws Exception {
        try (java.net.ServerSocket server = new java.net.ServerSocket(port, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            System.out.println("bridge listening on " + port + " at " + rate + " Hz");
            java.net.Socket socket = server.accept();
            socket.setTcpNoDelay(true);
            while (audio.getMode() != AudioManager.MODE_IN_CALL) Thread.sleep(100);
            System.out.println("in call; opening call audio");
            AudioRecord record = (AudioRecord) call(audio, "getCallDownlinkExtractionAudioRecord", new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build());
            AudioTrack track = (AudioTrack) call(audio, "getCallUplinkInjectionAudioTrack", new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build());
            record.startRecording();
            track.play();
            java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean(true);
            Thread uplink = new Thread(() -> {
                byte[] buf = new byte[rate / 50 * 2];
                try (java.io.InputStream in = socket.getInputStream()) {
                    while (running.get()) {
                        int n = in.read(buf);
                        if (n < 0) break;
                        track.write(buf, 0, n & ~1);
                    }
                } catch (IOException ignored) {
                }
                running.set(false);
            });
            Thread watcher = new Thread(() -> {
                while (running.get()) {
                    if (audio.getMode() != AudioManager.MODE_IN_CALL) {
                        System.out.println("call ended (mode " + modeName(audio.getMode()) + ")");
                        running.set(false);
                    }
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            });
            uplink.start();
            watcher.start();
            byte[] buf = new byte[rate / 50 * 2];
            try (java.io.OutputStream out = socket.getOutputStream()) {
                while (running.get()) {
                    int n = record.read(buf, 0, buf.length);
                    if (n < 0) throw new IOException("read failed: " + n);
                    out.write(buf, 0, n);
                }
            } catch (IOException e) {
                System.out.println("client gone: " + e.getMessage());
            }
            running.set(false);
            record.stop();
            record.release();
            track.stop();
            track.release();
            socket.close();
        }
    }

    private static short[] tone(int seconds) {
        short[] pcm = new short[RATE * seconds];
        for (int i = 0; i < pcm.length; i++) {
            double t = (double) i / RATE;
            double hz = (i / (RATE / 2)) % 2 == 0 ? 440 : 660;
            pcm[i] = (short) (Math.sin(2 * Math.PI * hz * t) * 8000);
        }
        return pcm;
    }

    private static void installShellApplication() throws Exception {
        Class<?> threadClass = Class.forName("android.app.ActivityThread");
        Constructor<?> constructor = threadClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        activityThread = constructor.newInstance();
        set(threadClass, null, "sCurrentActivityThread", activityThread);
        Field system = threadClass.getDeclaredField("mSystemThread");
        system.setAccessible(true);
        system.setBoolean(activityThread, true);
        try {
            Class<?> controllerClass = Class.forName("android.app.ConfigurationController");
            Constructor<?> c = controllerClass.getDeclaredConstructor(Class.forName("android.app.ActivityThreadInternal"));
            c.setAccessible(true);
            set(threadClass, activityThread, "mConfigurationController", c.newInstance(activityThread));
        } catch (Throwable ignored) {
        }
        try {
            Class<?> bindClass = Class.forName("android.app.ActivityThread$AppBindData");
            Constructor<?> c = bindClass.getDeclaredConstructor();
            c.setAccessible(true);
            Object bind = c.newInstance();
            ApplicationInfo info = new ApplicationInfo();
            info.packageName = ShellContext.PACKAGE;
            set(bindClass, bind, "appInfo", info);
            set(threadClass, activityThread, "mBoundApplication", bind);
        } catch (Throwable ignored) {
        }
        Application app = Instrumentation.newApplication(Application.class, ShellContext.get());
        set(threadClass, activityThread, "mInitialApplication", app);
    }

    private static AudioManager audioManager() throws Exception {
        Constructor<AudioManager> constructor = AudioManager.class.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        return constructor.newInstance(ShellContext.get());
    }

    static Context systemContext() {
        try {
            Method m = activityThread.getClass().getDeclaredMethod("getSystemContext");
            return (Context) m.invoke(activityThread);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static Object call(Object target, String name, Object... args) throws Exception {
        for (Method m : target.getClass().getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == args.length) {
                try {
                    return m.invoke(target, args);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    throw (Exception) e.getCause();
                }
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static void set(Class<?> owner, Object target, String field, Object value) throws Exception {
        Field f = owner.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static String modeName(int mode) {
        switch (mode) {
            case AudioManager.MODE_NORMAL: return "NORMAL";
            case AudioManager.MODE_RINGTONE: return "RINGTONE";
            case AudioManager.MODE_IN_CALL: return "IN_CALL";
            case AudioManager.MODE_IN_COMMUNICATION: return "IN_COMMUNICATION";
            default: return String.valueOf(mode);
        }
    }

    private static short[] readWav(File file) throws IOException {
        byte[] bytes = new byte[(int) file.length()];
        try (FileInputStream in = new FileInputStream(file)) {
            int off = 0;
            while (off < bytes.length) off += in.read(bytes, off, bytes.length - off);
        }
        int pos = 12;
        while (pos + 8 <= bytes.length) {
            String id = new String(bytes, pos, 4, "US-ASCII");
            int size = le32(bytes, pos + 4);
            if (id.equals("data")) {
                short[] pcm = new short[size / 2];
                for (int i = 0; i < pcm.length; i++) {
                    pcm[i] = (short) ((bytes[pos + 8 + 2 * i] & 0xff) | (bytes[pos + 9 + 2 * i] << 8));
                }
                return pcm;
            }
            pos += 8 + size;
        }
        throw new IOException("no data chunk in " + file);
    }

    private static int le32(byte[] b, int at) {
        return (b[at] & 0xff) | (b[at + 1] & 0xff) << 8 | (b[at + 2] & 0xff) << 16 | (b[at + 3] & 0xff) << 24;
    }

    private static void writeWav(File file, short[] pcm) throws IOException {
        try (RandomAccessFile out = new RandomAccessFile(file, "rw")) {
            out.setLength(0);
            int dataBytes = pcm.length * 2;
            out.writeBytes("RIFF");
            out.writeInt(Integer.reverseBytes(36 + dataBytes));
            out.writeBytes("WAVEfmt ");
            out.writeInt(Integer.reverseBytes(16));
            out.writeShort(Short.reverseBytes((short) 1));
            out.writeShort(Short.reverseBytes((short) 1));
            out.writeInt(Integer.reverseBytes(RATE));
            out.writeInt(Integer.reverseBytes(RATE * 2));
            out.writeShort(Short.reverseBytes((short) 2));
            out.writeShort(Short.reverseBytes((short) 16));
            out.writeBytes("data");
            out.writeInt(Integer.reverseBytes(dataBytes));
            byte[] buf = new byte[dataBytes];
            for (int i = 0; i < pcm.length; i++) {
                buf[2 * i] = (byte) pcm[i];
                buf[2 * i + 1] = (byte) (pcm[i] >> 8);
            }
            out.write(buf);
        }
    }

    static final class ShellContext extends ContextWrapper {
        static final String PACKAGE = "com.android.shell";
        private static ShellContext instance;

        static synchronized ShellContext get() {
            if (instance == null) instance = new ShellContext();
            return instance;
        }

        private ShellContext() {
            super(systemContext());
        }

        @Override
        public String getPackageName() {
            return PACKAGE;
        }

        @Override
        public String getOpPackageName() {
            return PACKAGE;
        }

        @Override
        public AttributionSource getAttributionSource() {
            return new AttributionSource.Builder(Process.SHELL_UID).setPackageName(PACKAGE).build();
        }

        @Override
        public int getDeviceId() {
            return 0;
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }
    }
}
