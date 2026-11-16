package dev.skramer.dashcam;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.SystemClock;
import java.io.IOException;

/**
 * One camera ownership lease. The native helper restores alpha on release or process death.
 * <p>
 * On the first camera start after boot, startPreview returns before the kernel turns the
 * camera's display layer on, and the helper refuses a layer that's off. So a refused lease is
 * asked for again in the background until it's granted, the camera is released, or RETRY_MS
 * runs out (a unit the helper doesn't support, or the helper missing until a reboot).
 * <p>
 * restore() runs inside the camera release for reverse, which the guard allows 800 ms in all. So
 * the lock is never held across socket I/O (a background retry can't hold restore up), and every
 * reply is waited for at most IO_MS: the helper answers in well under a millisecond, and closing
 * the socket makes it restore anyway.
 */
final class CameraOverlay {
    private static final long RETRY_EVERY_MS = 250, RETRY_MS = 60000;
    /** Longest wait for any reply from the helper. */
    private static final int IO_MS = 150;

    /** Guards socket, wanted and generation, never across socket I/O. */
    private final Object lock = new Object();
    private LocalSocket socket;
    /** Between hide and restore: the camera is ours and its layer should be transparent. */
    private boolean wanted;
    /** Bumped by each hide and restore, so a lease granted for an earlier session is given back. */
    private int generation;

    void hide(final int width, final int height) {
        final int gen;
        synchronized (lock) {
            if (wanted) return;
            wanted = true;
            gen = ++generation;
        }
        if (lease(width, height, gen, true)) return;
        new Thread("camera overlay") {
            @Override
            public void run() {
                long t0 = SystemClock.uptimeMillis();
                while (SystemClock.uptimeMillis() - t0 < RETRY_MS) {
                    SystemClock.sleep(RETRY_EVERY_MS);
                    if (!current(gen)) return;
                    if (lease(width, height, gen, false)) {
                        Log.i("camera display layer transparent after " + (SystemClock.uptimeMillis() - t0) + " ms");
                        return;
                    }
                }
                Log.w("camera overlay unavailable; projected video will show the camera (reboot after install/update)");
            }
        }.start();
    }

    private boolean current(int gen) {
        synchronized (lock) {
            return wanted && generation == gen;
        }
    }

    /**
     * Asks the helper once, without holding lock. True if the layer is now transparent for this
     * session; a lease granted after the session ended is closed at once, which restores it.
     */
    private boolean lease(int width, int height, int gen, boolean first) {
        LocalSocket candidate = new LocalSocket();
        try {
            candidate.connect(new LocalSocketAddress("dev.skramer.dashcam.overlay"));
            candidate.setSoTimeout(IO_MS);
            candidate.getOutputStream().write(new byte[] {'H', (byte)(width >> 8), (byte)width,
                    (byte)(height >> 8), (byte)height});
            if (candidate.getInputStream().read() != 'H') throw new IOException("refused (layer not on yet, or unsupported)");
            synchronized (lock) {
                if (wanted && generation == gen) {
                    socket = candidate;
                    candidate = null;
                }
            }
            if (candidate != null) {
                close(candidate);  // released meanwhile: the helper restores on close
                return false;
            }
            if (first) Log.i("camera display layer transparent");
            return true;
        } catch (IOException e) {
            close(candidate);
            if (first) Log.i("camera overlay: " + e + "; retrying");
            return false;
        }
    }

    void restore() {
        LocalSocket current;
        synchronized (lock) {
            wanted = false;
            generation++;
            current = socket;
            socket = null;
        }
        if (current == null) return;
        long t0 = SystemClock.uptimeMillis();
        try {
            current.setSoTimeout(IO_MS);
            current.getOutputStream().write('S');
            if (current.getInputStream().read() != 'S') throw new IOException("no restore acknowledgement");
            Log.i("camera display layer restored (" + (SystemClock.uptimeMillis() - t0) + " ms)");
        } catch (IOException e) {
            // Closing below still makes the helper restore.
            Log.w("camera overlay restore after " + (SystemClock.uptimeMillis() - t0) + " ms: " + e);
        } finally {
            close(current);
        }
    }

    private static void close(LocalSocket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (IOException ignored) {
            // Gone already.
        }
    }
}
