package dev.skramer.dashcam.player;

import android.content.Context;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * The recorder service (the dashcam NEMESIS package). What it's doing comes from its status
 * file. What it should do goes in recorder.conf (the settings) in this app's own files
 * directory, which only this app can write and the recorder reads.
 */
final class RecorderLink {
    private static final File STATE = new File("/data/nemesis/dashcam");
    static final String CONF = "recorder.conf";
    /** The heartbeat is rewritten every second while the recorder runs. */
    private static final long HEARTBEAT_STALE_MS = 10_000;

    /** recording, reversing, live, reverse, caroff, off, unsupported, nodrive, starting; or null if there's no status. */
    String state;
    long since;
    boolean running;
    Boolean configuredEnabled;
    String out = "/storage/usb0/Dashcam";
    String mount = "/storage/usb0";
    int fps = 20, bitrate = 3_000_000, segment = 60, minFreePercent = 5;
    /** The most the loop of recordings may use, saved clips not counted; 0 for no limit. */
    long maxBytes;

    static RecorderLink read() {
        RecorderLink r = new RecorderLink();
        File heartbeat = new File(STATE, "heartbeat");
        r.running = Math.abs(System.currentTimeMillis() - heartbeat.lastModified()) < HEARTBEAT_STALE_MS;
        try (BufferedReader in = new BufferedReader(new FileReader(new File(STATE, "status")))) {
            for (String line; (line = in.readLine()) != null; ) {
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String k = line.substring(0, eq), v = line.substring(eq + 1);
                try {
                    switch (k) {
                        case "state": r.state = v; break;
                        case "enabled": r.configuredEnabled = Boolean.parseBoolean(v); break;
                        case "since": r.since = Long.parseLong(v); break;
                        case "out": r.out = v; break;
                        case "mount": r.mount = v; break;
                        case "fps": r.fps = Integer.parseInt(v); break;
                        case "bitrate": r.bitrate = Integer.parseInt(v); break;
                        case "segment": r.segment = Integer.parseInt(v); break;
                        case "minfree": r.minFreePercent = Integer.parseInt(v); break;
                        case "maxbytes": r.maxBytes = Long.parseLong(v); break;
                        default: break;
                    }
                } catch (NumberFormatException ignored) {
                    // keep the default
                }
            }
        } catch (IOException e) {
            // Recorder not installed, or an older one without a status file.
        }
        return r;
    }

    /** StatFs alone reports the backing internal filesystem when USB is absent. */
    static boolean driveMounted(String mount) {
        try (BufferedReader in = new BufferedReader(new FileReader("/proc/mounts"))) {
            for (String line; (line = in.readLine()) != null; ) {
                String[] parts = line.split("\\s+");
                if (parts.length > 1 && parts[1].equals(mount)) return true;
            }
        } catch (IOException ignored) { }
        return false;
    }

    boolean enabled() {
        return configuredEnabled != null ? configuredEnabled : !"off".equals(state);
    }

    /**
     * What the app asks of the recorder: recording on or off, and how much of the drive it may
     * use. Everything else is fixed.
     */
    static final class Settings {
        boolean enabled = true;
        /**
         * Size limit in GB, 0 for none; null leaves it to the recorder (its --max-gb, or none).
         * The recorder ignores values between 0 and 1.
         */
        Double maxGb;

        /**
         * The saved setting; without a file (first run, or after a package update reinstalled
         * the app) whatever the recorder is doing.
         */
        static Settings read(Context c) {
            Settings s = new Settings();
            File f = new File(c.getFilesDir(), CONF);
            if (!f.isFile()) {
                RecorderLink r = RecorderLink.read();
                if (r.state != null) s.enabled = r.enabled();
                if (r.maxBytes > 0) s.maxGb = r.maxBytes / 1e9;  // the recorder's copy kept it
                return s;
            }
            try (BufferedReader in = new BufferedReader(new FileReader(f))) {
                for (String line; (line = in.readLine()) != null; ) {
                    if (line.startsWith("enabled=")) s.enabled = Boolean.parseBoolean(line.substring(8).trim());
                    else if (line.startsWith("maxgb=")) {
                        try {
                            s.maxGb = Double.parseDouble(line.substring(6).trim());
                        } catch (NumberFormatException ignored) {
                            // leave it to the recorder
                        }
                    }
                }
            } catch (IOException e) {
                // nothing saved yet
            }
            return s;
        }

        /** Saves for the recorder, which picks it up within a second. */
        void write(Context c) throws IOException {
            StringBuilder b = new StringBuilder("enabled=").append(enabled).append('\n');
            if (maxGb != null) b.append("maxgb=").append(gb(maxGb)).append('\n');
            File dir = c.getFilesDir();
            File tmp = new File(dir, CONF + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(b.toString().getBytes(StandardCharsets.US_ASCII));
            }
            readableByRecorder(dir, tmp);
            if (!tmp.renameTo(new File(dir, CONF))) throw new IOException("can't replace " + CONF);
        }
    }

    /** The recorder runs as the system uid: it needs to pass through the files directory and read the file. */
    private static void readableByRecorder(File dir, File f) {
        dir.setExecutable(true, false);
        f.setReadable(true, false);
    }

    /** "64" or "0.5": the recorder reads either. */
    static String gb(double gb) {
        return gb == Math.rint(gb) ? String.valueOf((long) gb) : String.valueOf(gb);
    }
}
