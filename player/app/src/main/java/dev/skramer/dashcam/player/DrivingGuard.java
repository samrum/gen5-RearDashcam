package dev.skramer.dashcam.player;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * Playback is for when the car is parked. Gen5W's com.hkmc.regulation service says when the car
 * is out of park, but leaves it to each app to obey. Every screen checks it when created and
 * resumed: if the car is regulated, Hyundai's own "not available while driving" popup is shown,
 * every screen is finished and the process exits. A receiver does the same when the car leaves
 * park (popup only if the app was in use), and so does a check every few seconds, in case the
 * broadcast is missed.
 * <p>
 * It fails closed: on a Hyundai head unit, a regulation service that's missing or doesn't answer
 * counts as driving. Only a device without Hyundai's services at all (an emulator) counts as parked.
 */
final class DrivingGuard extends BroadcastReceiver {
    private static final String SERVICE = "com.hkmc.regulation";
    private static final String DESCRIPTOR = "com.hkmc.regulation.IRegulationManager";
    private static final int TX_GET_STATUS = 1;
    private static final int TX_SHOW_POPUP = 8;
    private static final String ENTER = "android.mobis.regulation.ACTION_ENTER_REGULATION";
    /** Any of Hyundai's own services: present on every Gen5W head unit, absent on an emulator. */
    private static final String HKMC_SERVICE = "com.hkmc.micom.system";
    private static final long POLL_MS = 3000;

    private static final ArrayList<WeakReference<Activity>> activities = new ArrayList<>();
    private static DrivingGuard receiver;
    private static Handler poller;
    private static final Runnable poll = new Runnable() {
        @Override
        public void run() {
            Activity live = null;
            for (WeakReference<Activity> r : activities) {
                Activity a = r.get();
                if (a != null && !a.isFinishing()) live = a;
            }
            if (live == null) return;  // started again by the next screen's onCreate
            if (status() != 0) {
                stop(live.getString(R.string.driving), live.getPackageName(), inUse());
                return;
            }
            poller.postDelayed(this, POLL_MS);
        }
    };

    static void onCreate(Activity activity) {
        activities.add(new WeakReference<>(activity));
        if (receiver == null) {
            receiver = new DrivingGuard();
            activity.getApplicationContext().registerReceiver(receiver, new IntentFilter(ENTER));
        }
        if (poller == null) poller = new Handler(Looper.getMainLooper());
        poller.removeCallbacks(poll);
        poller.postDelayed(poll, POLL_MS);
        check(activity);
    }

    /** Closes the app if the car is out of park. Returns true if it did. */
    static boolean check(Activity activity) {
        if (status() == 0) return false;
        stop(activity.getString(R.string.driving), activity.getPackageName(), true);
        return true;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        stop(context.getString(R.string.driving), context.getPackageName(), inUse());
    }

    /** True if the app is on screen (or close to it), so closing it deserves the popup. */
    private static boolean inUse() {
        ActivityManager.RunningAppProcessInfo me = new ActivityManager.RunningAppProcessInfo();
        ActivityManager.getMyMemoryState(me);
        return me.importance < ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND;
    }

    private static void stop(String message, String pkg, boolean popup) {
        if (popup) {
            try {
                call(TX_SHOW_POPUP, message, pkg);
            } catch (Throwable ignored) {
                // closing is what matters
            }
        }
        for (int i = activities.size() - 1; i >= 0; i--) {
            Activity a = activities.get(i).get();
            if (a != null) a.finishAffinity();
        }
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    private static IBinder service() {
        return service(SERVICE);
    }

    private static IBinder service(String name) {
        try {
            return (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, name);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * IRegulationManager.getStatus(): 0 while parked. If it can't say, 1 (driving) on a Hyundai
     * head unit, and 0 on a device without Hyundai's services.
     */
    private static int status() {
        try {
            if (service() == null) return service(HKMC_SERVICE) != null ? 1 : 0;
            return call(TX_GET_STATUS);
        } catch (Throwable t) {
            return 1;
        }
    }

    private static int call(int code, String... args) throws Exception {
        IBinder b = service();
        if (b == null) return 0;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            for (String s : args) data.writeString(s);
            b.transact(code, data, reply, 0);
            reply.readException();
            return code == TX_GET_STATUS ? reply.readInt() : 0;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }
}
