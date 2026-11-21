package dev.skramer.dashcam;

import android.app.ActivityManagerNative;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ServiceManager;
import android.os.SystemClock;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Keeps the stock reverse view working. The dashcam and the stock rear camera share one video
 * input, and while the dashcam holds it the stock view can't open at all. So the camera is
 * released whenever the stock app wants it (it broadcasts CAMERA_APP_START, for reverse and for
 * the camera button) or the gear reads R, and only taken back once the gear is out of R and the
 * stock app has closed its camera. Nothing is recorded meanwhile (state "reverse"): the recording
 * ends when the camera is handed over and a new one starts when it's taken back. If a release
 * hangs, the process kills itself, which frees the camera too; the service script restarts it.
 * The heartbeat file lets the watchdog service kill a process that stopped responding altogether.
 *
 * The CAMERA_APP_START receiver is a second line: this firmware's ActivityManager skips
 * receivers registered without an app process, so on Gen5W it's the gear polling (which reads 1
 * for the camera button too) that does the work.
 *
 * The player app's settings file turns recording off and on. The status file says what the
 * recorder is doing.
 *
 * While the mirror's live view is wanted and nothing is being recorded (recording off, or no
 * drive), the camera runs a preview session for it instead (state "live"), with the same
 * hand-off to the stock view.
 *
 * It only records while the ignition is on. The head unit also starts up while the car is off
 * (every half hour or so overnight, for the car's own checks), and then the camera has no power
 * and delivers noise.
 */
final class Guard extends Thread {
    private static final String STOCK_CAMERA_START = "com.hkmc.intent.action.CAMERA_APP_START";
    private static final int GEAR_REVERSE = 1;
    /** While we own the camera, detect reverse within 50 ms; other work stays at 250 ms. */
    private static final long POLL_MS = 50, STATE_POLL_MS = 250;
    /** After the last reverse or stock-camera signal, wait this long before taking the camera back. */
    private static final long SETTLE_MS = 3000;
    private static final long RELEASE_TIMEOUT_MS = 800;
    private static final long STORAGE_NOTICE_MS = 60000;
    private static final int UNSUPPORTED_CAMERA_TYPE = 6;
    private static final long RETRY_MS = 5000;
    /** The status-bar dot shows while a frame has gone into a segment file within this long. */
    private static final long WRITING_MS = 3000;
    /** The ignition reading off this many polls in a row (about a second) means the car is off. */
    private static final int IGNITION_OFF_POLLS = 4;
    /**
     * The gear unreadable this many polls in a row counts as reverse: without it there's no telling
     * when the stock view needs the camera (this firmware doesn't deliver CAMERA_APP_START here).
     */
    private static final int GEAR_UNKNOWN_POLLS = 3;
    /**
     * The main thread opens, hands off and releases the camera. If it hasn't run a check-in for
     * this long, the heartbeat stops so the watchdog kills the process; in reverse, much sooner,
     * the process kills itself: a camera call hung there would keep the stock view out.
     */
    private static final long MAIN_STALL_MS = 5000, REVERSE_MAIN_STALL_MS = 1000;

    private final Config config;
    private final Recorder recorder;
    private final Storage storage;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final File heartbeat, status, appConf;
    private final Method gearStatus;
    private final StockCameraWatcher watcher = new StockCameraWatcher();
    private final MirrorFeed mirror;
    private final StatusIcon statusIcon = new StatusIcon();
    private boolean driveMounted;
    /** Until then (uptime ms), take down SystemUI's USB notice every state poll: a minute from startup or a mount, the scan's length. */
    private long hideStorageUntil = SystemClock.uptimeMillis() + STORAGE_NOTICE_MS;
    private boolean storageNoticeFailed;
    private final Runnable startRecorder = new Runnable() {
        @Override
        public void run() {
            recorder.start();
        }
    };
    private final Runnable startPreview = new Runnable() {
        @Override
        public void run() {
            recorder.startPreview();
        }
    };
    private final Runnable startEncoding = new Runnable() {
        @Override
        public void run() {
            recorder.startEncoding();
        }
    };
    private final Runnable stopEncoding = new Runnable() {
        @Override
        public void run() {
            recorder.stopEncoding();
        }
    };
    private volatile long lastBlockMs = -SETTLE_MS;
    /** Uptime of the main-thread check-in waiting to run, or 0 if none is. */
    private volatile long mainCheckPostedMs;
    private final Runnable mainCheck = new Runnable() {
        @Override
        public void run() {
            mainCheckPostedMs = 0;
        }
    };
    private boolean mainStallLogged;
    private boolean disabled;
    private long appConfModified, appConfLength;
    private int lastGear = -1;
    private int gearUnknownPolls;
    private int ignitionOffPolls;
    private Boolean lastIgnition;
    private String lastState;

    Guard(Config config, Recorder recorder, MirrorFeed mirror) {
        super("guard");
        this.config = config;
        this.recorder = recorder;
        this.mirror = mirror;
        storage = new Storage(config);
        heartbeat = new File(config.stateDir, "heartbeat");
        status = new File(config.stateDir, "status");
        appConf = new File(config.appConf);
        appConfModified = appConf.lastModified();
        appConfLength = appConf.length();
        disabled = !config.enabled;
        gearStatus = gearMethod();
        setDaemon(true);
    }

    @Override
    public void run() {
        try {
            ActivityManagerNative.getDefault().registerReceiver(
                    null, null, watcher, new IntentFilter(STOCK_CAMERA_START), null, 0);
            Log.i("listening for " + STOCK_CAMERA_START);
        } catch (Throwable t) {
            Log.e("can't listen for the stock camera; the camera button won't work while recording", t);
        }
        if (disabled) Log.i("recording is turned off in the app");
        // Camera type 6 (360-view cars' digital camera) sends video only while the car shows its own
        // camera view. The installer refuses these cars; this is in case its check couldn't tell.
        final boolean noGear = gearStatus == null;
        final boolean unsupported = noGear || RearCam.type() == UNSUPPORTED_CAMERA_TYPE;
        if (noGear) Log.w("no way to read the gear, so reverse can't be detected; not using the camera");
        else if (unsupported) Log.w("camera type " + UNSUPPORTED_CAMERA_TYPE + " isn't supported (360 view cars); not recording");
        boolean wasPreview = false;
        long nextStart = 0;
        boolean mounted = true;
        long nextStatePoll = 0;
        for (long beat = 0; ; ) {
            try {
                long now = SystemClock.uptimeMillis();
                long mainStalled = mainStalledMs(now);
                int gear = gear();
                if (gear != lastGear) {
                    Log.i("gear status " + gear);
                    lastGear = gear;
                }
                // Fail safe: a gear that can't be read might be reverse.
                gearUnknownPolls = gear < 0 ? gearUnknownPolls + 1 : 0;
                boolean gearUnknown = gearUnknownPolls >= GEAR_UNKNOWN_POLLS;
                if (gearUnknownPolls == GEAR_UNKNOWN_POLLS) Log.w("can't read the gear; treating it as reverse until it reads again");
                if (gear == GEAR_REVERSE || gearUnknown) {
                    mirror.allow(false);
                    lastBlockMs = now;
                    if (mainStalled >= REVERSE_MAIN_STALL_MS) {
                        dieForReverse("the main thread has been stuck for " + mainStalled + " ms in reverse");
                    }
                    if (recorder.isActive()) handOff(gearUnknown ? "gear unreadable" : "reverse");
                }
                if (now < nextStatePoll) {
                    SystemClock.sleep(recorder.isActive() ? POLL_MS : STATE_POLL_MS);
                    continue;
                }
                nextStatePoll = now + STATE_POLL_MS;
                boolean carOff = carOff();
                boolean blocked = now - lastBlockMs < SETTLE_MS;
                if (beat % 4 == 0) checkAppSettings();
                // A failed write is usually the drive being pulled: hold off recording, and look again now.
                boolean writeFailed = now - recorder.writeFailedMs() < RETRY_MS;
                if (beat % 4 == 0 || !driveMounted || writeFailed) {  // every poll while out, to catch a mount quickly
                    boolean was = driveMounted;
                    driveMounted = storage.mounted();
                    if (driveMounted && !was) hideStorageUntil = now + STORAGE_NOTICE_MS;
                }
                if (now < hideStorageUntil && !Car.hideStorageNotification() && !storageNoticeFailed) {
                    storageNoticeFailed = true;
                    Log.w("can't take down the USB status-bar notice");
                }
                boolean canRecord = !unsupported && !disabled && driveMounted && !writeFailed;
                // Only bootstrap the shared camera when no recording session is needed.
                boolean wantPreview = mirror.requested() && !unsupported && !canRecord;
                if (wantPreview != wasPreview) { wasPreview = wantPreview; nextStart = 0; }
                boolean off = !wantPreview && (unsupported || disabled);
                String offState = unsupported ? "unsupported" : "off";
                String state;
                if (recorder.isActive()) {
                    if (carOff) {
                        release("ignition off");
                    } else if (recorder.isPreview() != wantPreview && !blocked
                            && (wantPreview || canRecord)) {
                        // Recording starts or stops in the running session: reopening the camera
                        // would blank the overlay and the mirror.
                        main.post(wantPreview ? stopEncoding : startEncoding);
                    } else if (recorder.isPreview() != wantPreview) {
                        release(wantPreview ? "recording stopped; live view only" : "live view closed");
                        nextStart = 0;
                    } else if (off) {
                        release("turned off");
                    } else if (blocked) {
                        handOff(gear == GEAR_REVERSE ? "reverse" : "stock camera");
                    }
                    state = !recorder.isActive() ? (carOff ? "caroff" : off ? offState : blocked ? "reverse" : "starting")
                            : recorder.isPreview() ? "live" : "recording";
                } else if (carOff) {
                    state = "caroff";
                } else if (off) {
                    state = offState;
                } else if (blocked) {
                    state = "reverse";
                } else {
                    if (now >= nextStart) {
                        nextStart = now + RETRY_MS;
                        mounted = wantPreview || (driveMounted && !writeFailed);
                        if (!mounted) {
                            if (beat % 120 == 0) Log.i("waiting for " + config.mount);
                        } else if (stockCameraOpen()) {
                            Log.i("the stock view still has the camera; waiting");
                            lastBlockMs = now;  // the stock view is still up; check again after SETTLE_MS
                        } else {
                            main.post(wantPreview ? startPreview : startRecorder);
                        }
                    }
                    state = mounted ? "starting" : "nodrive";
                }
                writeStatus(state);
                boolean writing = recorder.isActive() && !recorder.isPreview() && now - recorder.lastWriteMs() < WRITING_MS;
                statusIcon.show(writing);
                mirror.recording(writing ? MirrorFeed.RECORDING : disabled ? MirrorFeed.DISABLED
                        : !driveMounted ? MirrorFeed.NO_DRIVE : MirrorFeed.OTHER);
                mirror.allow(!carOff && !blocked && !unsupported && recorder.isActive());
                if (beat % 4 == 0) {
                    if (mainStalled < MAIN_STALL_MS) {
                        writeHeartbeat(beat / 4);
                        mainStallLogged = false;
                    } else if (!mainStallLogged) {
                        Log.w("the main thread has been stuck for " + mainStalled + " ms; stopping the heartbeat");
                        mainStallLogged = true;
                    }
                }
                beat++;
                SystemClock.sleep(recorder.isActive() ? POLL_MS : STATE_POLL_MS);
            } catch (Throwable t) {
                Log.e("guard loop", t);
                SystemClock.sleep(STATE_POLL_MS);
            }
        }
    }

    /**
     * True if the ignition is off: at once on the first reading, so a head unit that started up
     * with the car off never opens the camera; after that, once it has read off for
     * IGNITION_OFF_POLLS polls in a row. If the car can't say, it counts as on.
     */
    private boolean carOff() {
        Boolean on = Car.ignitionOn();
        if (on == null) on = true;
        if (on) ignitionOffPolls = 0;
        else if (lastIgnition == null) ignitionOffPolls = IGNITION_OFF_POLLS;
        else ignitionOffPolls = Math.min(ignitionOffPolls + 1, IGNITION_OFF_POLLS);
        if (!on.equals(lastIgnition)) {
            Log.i("ignition " + (on ? "on" : "off") + " (" + Car.describe() + ")");
            lastIgnition = on;
        }
        return ignitionOffPolls >= IGNITION_OFF_POLLS;
    }

    /** The stock app is about to open the rear camera: let go of it now. */
    void onStockCamera() {
        Log.i("the stock camera app is starting");
        lastBlockMs = SystemClock.uptimeMillis();
        if (recorder.isActive()) handOff("stock camera");
    }

    /**
     * Stops the recorder for the stock view, as release does, but waits only for the camera
     * itself: the guide lines, the feed's threads, the encoder and the segment are ended after
     * that, outside the deadline.
     */
    private synchronized void handOff(String why) {
        if (!recorder.isActive()) return;
        Log.i("handing the camera over: " + why);
        final CountDownLatch released = new CountDownLatch(1);
        main.postAtFrontOfQueue(new Runnable() {
            @Override
            public void run() {
                recorder.stop(new Runnable() {
                    @Override
                    public void run() {
                        released.countDown();
                    }
                });
            }
        });
        awaitRelease(released);
    }

    /** Stops the recorder on the camera thread; if that doesn't finish in time, dies instead. */
    private synchronized void release(String why) {
        if (!recorder.isActive()) return;
        Log.i("releasing the camera: " + why);
        CountDownLatch done = new CountDownLatch(1);
        main.postAtFrontOfQueue(new Runnable() {
            @Override
            public void run() {
                recorder.stop();
                done.countDown();
            }
        });
        awaitRelease(done);
    }

    /**
     * How long the main thread has left a check-in waiting, posting a new one if none is: 0
     * while it keeps up. A camera call that hangs there (open, start, release) shows up here.
     */
    private long mainStalledMs(long now) {
        long posted = mainCheckPostedMs;
        if (posted != 0) return now - posted;
        mainCheckPostedMs = now;
        main.post(mainCheck);
        return 0;
    }

    /** Exits, which frees the camera for the stock view; the service script starts the recorder again. */
    private static void dieForReverse(String why) {
        Log.e(why + "; exiting to free the camera", null);
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    /** Waits for the camera to be released; if it isn't in time, dies, which frees it. */
    private static void awaitRelease(CountDownLatch done) {
        boolean ok;
        try {
            ok = done.await(RELEASE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            ok = false;
        }
        if (!ok) dieForReverse("camera release took over " + RELEASE_TIMEOUT_MS + " ms");
    }

    /** RearCamera.getGearStatus(): asks the Cortex-M (/dev/tcc_cm_ctrl); 1 is reverse. */
    private static Method gearMethod() {
        try {
            return Class.forName("android.hardware.RearCamera").getMethod("getGearStatus");
        } catch (Throwable t) {
            Log.e("no RearCamera.getGearStatus(); reverse can't be detected", t);
            return null;
        }
    }

    private int gear() {
        if (gearStatus == null) return -1;
        try {
            return (Integer) gearStatus.invoke(null);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** True if the stock app has the rear camera open, per the rear camera service's dump. */
    private boolean stockCameraOpen() {
        IBinder service = ServiceManager.getService("media.rearcamera");
        if (service == null) return false;
        File dump = null;
        try {
            // A new file each time: a fixed name in the shared state directory could be a planted link.
            dump = File.createTempFile(".rearcamera-", ".txt", new File(config.stateDir));
            try (FileOutputStream out = new FileOutputStream(dump)) {
                service.dump(out.getFD(), new String[0]);
            }
            byte[] b = new byte[(int) Math.min(dump.length(), 64 * 1024)];
            try (FileInputStream in = new FileInputStream(dump)) {
                int n = in.read(b);
                return n > 0 && new String(b, 0, n, StandardCharsets.UTF_8).contains("Device is open");
            }
        } catch (Throwable t) {
            Log.e("can't check the rear camera service", t);
            return false;
        } finally {
            if (dump != null) dump.delete();
        }
    }

    /** Picks up recording being turned on or off in the app. */
    private void checkAppSettings() {
        long m = appConf.lastModified(), length = appConf.length();
        // KitKat can report file timestamps only to the second. enabled=true/false
        // have different lengths, so rapid toggles must also compare the length. (A size limit
        // changed twice within a second to one as long misses the second change until the next
        // write; the app's dialog can't be that quick.)
        if (m == appConfModified && length == appConfLength) return;
        appConfModified = m;
        appConfLength = length;
        Config fresh = config.withAppSettings();
        saveSettingsCopy();
        if (fresh.maxBytes != config.maxBytes) {
            config.maxBytes = fresh.maxBytes;  // the writer's Storage reads it before each segment
            lastState = null;  // the status file shows it
            Log.i("recordings may now use " + Config.describeLimit(fresh.maxBytes) + " (set in the app)");
        }
        if (fresh.enabled == !disabled) return;
        disabled = !fresh.enabled;
        Log.i("recording turned " + (fresh.enabled ? "on" : "off") + " in the app");
    }

    /** Copies the app's settings file to the state directory, so they outlive a reinstall of the app. */
    private void saveSettingsCopy() {
        if (!appConf.isFile()) return;
        java.io.ByteArrayOutputStream copy = new java.io.ByteArrayOutputStream();
        try (FileInputStream in = new FileInputStream(appConf)) {
            byte[] b = new byte[4096];
            for (int n; (n = in.read(b)) > 0 && copy.size() < 64 * 1024; ) copy.write(b, 0, n);
            StateFile.write(config.savedSettings(), copy.toByteArray(), false);
        } catch (IOException e) {
            Log.e("can't keep a copy of the app's settings", e);
        }
    }

    /**
     * What the player app shows: "recording", "live" (the camera runs for the mirror only),
     * "reverse" (the camera is with the reverse view; nothing is recorded),
     * "caroff" (ignition off), "off", "unsupported" (camera type 6), "nodrive" or "starting", and since when. Written only when
     * it changes, readable by any app. The heartbeat's age says whether the recorder is running.
     */
    private void writeStatus(String state) {
        String key = state + " " + driveMounted + " " + disabled;
        if (key.equals(lastState)) return;
        String text = "state=" + state + "\nenabled=" + !disabled + "\nsince=" + System.currentTimeMillis() + "\nout=" + config.outDir
                + "\nmount=" + config.mount + "\nfps=" + config.fps + "\nbitrate=" + config.bitrate
                + "\nsegment=" + config.segmentSeconds + "\nminfree=" + config.minFreePercent + "\nmaxbytes=" + config.maxBytes
                + "\ndrive=" + driveMounted + "\n";
        try {
            StateFile.write(status, text.getBytes(StandardCharsets.US_ASCII), true);
        } catch (IOException e) {
            Log.e("can't write " + status, e);
            return;
        }
        lastState = key;
    }

    /** "pid count": the watchdog service kills the process if the count stops changing. */
    private void writeHeartbeat(long count) {
        try {
            // Readable: the watchdog runs as NEMESIS root, which can't read past file permissions.
            StateFile.write(heartbeat, (android.os.Process.myPid() + " " + count + "\n").getBytes(StandardCharsets.US_ASCII), true);
        } catch (IOException e) {
            if (count % 60 == 0) Log.e("can't write " + heartbeat, e);
        }
    }

    /** Receives CAMERA_APP_START without an app Context, registered straight with the ActivityManager. */
    private final class StockCameraWatcher extends IIntentReceiver.Stub {
        @Override
        public void performReceive(Intent intent, int resultCode, String data, Bundle extras,
                boolean ordered, boolean sticky, int sendingUser) {
            onStockCamera();
            if (ordered) {
                try {
                    ActivityManagerNative.getDefault().finishReceiver(this, resultCode, data, extras, false);
                } catch (Throwable t) {
                    Log.e("finishReceiver", t);
                }
            }
        }
    }
}
