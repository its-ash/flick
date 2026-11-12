import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

/**
 * Dev tool: records motion sensors over ADB without installing an app (runs with app_process
 * as the shell user). Prints CSV to stdout, time in ms since start (sensor event clock):
 *   L,t,x,y,z   linear acceleration (m/s², gravity removed)
 *   A,t,x,y,z   raw accelerometer
 *   G,t,x,y,z   gyroscope (rad/s)
 *   CHOP,t,v    Motorola ChopChop hardware gesture fired
 *   TORCH,t,on|off
 * Build and run: see tools/sensorrec/README.md.
 */
public final class SensorRec {
    static final long T0 = SystemClock.elapsedRealtimeNanos();

    static synchronized void out(String s) {
        System.out.println(s);
        System.out.flush();
    }

    static String ms(long ns) {
        return String.format(java.util.Locale.US, "%.1f", (ns - T0) / 1e6);
    }

    public static void main(String[] args) throws Throwable {
        int seconds = args.length > 0 ? Integer.parseInt(args[0]) : 60;
        Looper.prepareMainLooper();
        Context sys = systemContext();
        Context ctx = new ContextWrapper(sys) {
            @Override public String getOpPackageName() { return "com.android.shell"; }
            @Override public String getPackageName() { return "com.android.shell"; }
            @Override public String getAttributionTag() { return null; }
            @Override public Context getApplicationContext() { return this; }
            @Override public AttributionSource getAttributionSource() {
                return new AttributionSource.Builder(android.os.Process.myUid()).setPackageName("com.android.shell").build();
            }
        };

        SensorManager sm = (SensorManager) Class.forName("android.hardware.SystemSensorManager")
                .getConstructor(Context.class, Looper.class).newInstance(ctx, Looper.getMainLooper());

        SensorEventListener l = new SensorEventListener() {
            @Override public void onSensorChanged(SensorEvent e) {
                int type = e.sensor.getType();
                String t = ms(e.timestamp);
                if (type == Sensor.TYPE_LINEAR_ACCELERATION) out("L," + t + "," + e.values[0] + "," + e.values[1] + "," + e.values[2]);
                else if (type == Sensor.TYPE_ACCELEROMETER) out("A," + t + "," + e.values[0] + "," + e.values[1] + "," + e.values[2]);
                else if (type == Sensor.TYPE_GYROSCOPE) out("G," + t + "," + e.values[0] + "," + e.values[1] + "," + e.values[2]);
                else out("CHOP," + t + "," + e.values[0]);
            }
            @Override public void onAccuracyChanged(Sensor s, int a) {}
        };

        reg(sm, l, sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION, false), 5000);
        reg(sm, l, sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, false), 2000);
        reg(sm, l, sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE, false), 5000);
        Sensor chop = null;
        for (Sensor s : sm.getSensorList(Sensor.TYPE_ALL)) {
            if (s.getType() == 65546 || "com.motorola.sensor.chopchop".equals(s.getStringType())) chop = s;
        }
        if (chop != null) reg(sm, l, chop, 0);
        else out("# no ChopChop sensor");

        watchTorch(ctx);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            out("# done");
            System.exit(0);
        }, seconds * 1000L);
        out("# recording " + seconds + " s");
        Looper.loop();
    }

    static void reg(SensorManager sm, SensorEventListener l, Sensor s, int us) {
        if (s == null) return;
        boolean ok = sm.registerListener(l, s, us, 0);
        out("# " + s.getName() + " type=" + s.getType() + " registered=" + ok);
    }

    static Context systemContext() throws Exception {
        Class<?> at = Class.forName("android.app.ActivityThread");
        Constructor<?> c = at.getDeclaredConstructor();
        c.setAccessible(true);
        Object thread = c.newInstance();
        Field f = at.getDeclaredField("sCurrentActivityThread");
        f.setAccessible(true);
        f.set(null, thread);
        return (Context) at.getDeclaredMethod("getSystemContext").invoke(thread);
    }

    /** Torch state: CameraManager callback, else polls Settings.Secure flashlight_enabled. */
    static void watchTorch(Context ctx) {
        try {
            CameraManager cm = CameraManager.class.getConstructor(Context.class).newInstance(ctx);
            cm.registerTorchCallback(new CameraManager.TorchCallback() {
                @Override public void onTorchModeChanged(String id, boolean on) {
                    out("TORCH," + ms(SystemClock.elapsedRealtimeNanos()) + "," + (on ? "on" : "off") + ",cam" + id);
                }
            }, new Handler(Looper.getMainLooper()));
            out("# torch: camera callback");
            return;
        } catch (Throwable e) {
            out("# torch callback failed: " + e);
        }
        Thread poll = new Thread(() -> {
            String last = null;
            while (true) {
                try {
                    Process p = Runtime.getRuntime().exec(new String[]{"settings", "get", "secure", "flashlight_enabled"});
                    String v = new BufferedReader(new InputStreamReader(p.getInputStream())).readLine();
                    p.waitFor();
                    if (v != null && !v.equals(last)) {
                        out("TORCH," + ms(SystemClock.elapsedRealtimeNanos()) + "," + ("1".equals(v.trim()) ? "on" : "off") + ",poll");
                        last = v;
                    }
                    Thread.sleep(100);
                } catch (Exception ignored) {}
            }
        });
        poll.setDaemon(true);
        poll.start();
        out("# torch: polling settings");
    }
}
