package dev.skramer.dashcam;

import android.os.StatFs;
import android.os.SystemClock;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FilenameFilter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** The recording folder on the USB drive: mount check, segment names, deleting old segments. */
final class Storage {
    /**
     * How often the folder's size is measured again from scratch. In between it's kept up to date
     * from the segments written and deleted here; a rescan picks up what the app deleted or saved.
     */
    static final long RESCAN_MS = 30 * 60_000L;

    private final Config config;
    private final File dir;
    private final SimpleDateFormat names = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);

    // For the size limit. Measuring the folder means a stat for every file, thousands of them over
    // the drive's FUSE mount, which can take seconds: too long for the writer thread, which has
    // about 10 s of queue. So it's measured on a thread of its own, and kept up to date here.
    /** Segments in the recording folder, name to size: names sort oldest first. Null until measured. */
    private TreeMap<String, Long> loop;
    /** Bytes of the segments in loop. */
    private long loopBytes;
    /** SystemClock.uptimeMillis() of the last measurement's start; and whether one is running. */
    private long measuredAt;
    private boolean measuring;
    /** The segment handed out last, whose size is added once it's finished. */
    private File current;

    Storage(Config config) {
        this.config = config;
        dir = new File(config.outDir);
    }

    private void ensureDir() throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("can't create " + dir);
    }

    /** True if the drive is mounted. Without this check, recording would fill internal storage. */
    boolean mounted() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/mounts"))) {
            for (String line; (line = r.readLine()) != null; ) {
                String[] f = line.split(" ");
                if (f.length > 1 && f[1].equals(config.mount)) return true;
            }
        } catch (IOException e) {
            Log.e("can't read /proc/mounts", e);
        }
        return false;
    }

    /** A new segment file, named for the current time. */
    File newSegment() throws IOException {
        ensureDir();
        String base = names.format(new Date());
        File f = new File(dir, base + ".ts");
        for (int i = 1; f.exists(); i++) f = new File(dir, base + "_" + i + ".ts");
        synchronized (this) {
            current = f;
        }
        return f;
    }

    /**
     * Deletes the oldest segments until they're under the size limit with room for one more, and
     * the configured share of the drive is free. Saved clips and snapshots don't count.
     */
    void makeRoom() throws IOException {
        ensureDir();
        long max = config.maxBytes;
        if (max > 0) keepUnder(max);
        keepFree();
    }

    /** Space for the next segment: its share of the bitrate, and a margin for the container. */
    private long nextSegmentBytes() {
        return (long) config.bitrate / 8 * config.segmentSeconds * 11 / 10;
    }

    /** Size limit on the loop of segments. Until the first measurement finishes, only keepFree applies. */
    private synchronized void keepUnder(long max) {
        if (!measuring && (loop == null || SystemClock.uptimeMillis() - measuredAt >= RESCAN_MS)) measure();
        if (loop == null) return;
        if (current != null) {
            // The segment just finished, unless the save button moved it to Saved.
            if (current.isFile()) add(current.getName(), current.length());
            current = null;
        }
        long reserve = nextSegmentBytes();
        while (loopBytes + reserve > max && !loop.isEmpty()) {
            Map.Entry<String, Long> oldest = loop.pollFirstEntry();
            loopBytes -= oldest.getValue();
            File f = new File(dir, oldest.getKey());
            if (!f.exists()) continue;  // deleted in the app, or saved
            if (f.delete()) {
                Log.i("deleted " + f.getName() + " to stay " + Config.describeLimit(max));
            } else {
                Log.w("can't delete " + f);
                return;
            }
        }
    }

    /**
     * Measures the folder again before the next segment: recording stopped, and the drive may
     * have been pulled, swapped or changed on a computer since.
     */
    synchronized void remeasure() {
        measuredAt = SystemClock.uptimeMillis() - RESCAN_MS;
    }

    private void add(String name, long bytes) {
        Long was = loop.put(name, bytes);
        loopBytes += bytes - (was == null ? 0 : was);
    }

    /** Starts measuring the folder on a thread of its own. */
    private void measure() {
        measuring = true;
        measuredAt = SystemClock.uptimeMillis();
        Thread t = new Thread("storage-measure") {
            @Override
            public void run() {
                TreeMap<String, Long> segments = new TreeMap<>();
                long started = SystemClock.uptimeMillis();
                try {
                    File[] files = dir.listFiles();
                    if (files != null) {
                        for (File f : files) {
                            if (f.getName().endsWith(".ts") && f.isFile()) segments.put(f.getName(), f.length());
                        }
                    }
                } catch (RuntimeException e) {  // the drive went away
                    Log.e("can't measure " + dir, e);
                    segments = null;
                }
                long took = SystemClock.uptimeMillis() - started;
                synchronized (Storage.this) {
                    measuring = false;
                    if (segments == null) return;
                    // Segments finished while this ran aren't in the listing, and the one being
                    // written was listed part-written: keep what was counted for those.
                    if (loop != null && !segments.isEmpty()) {
                        segments.putAll(loop.tailMap(segments.lastKey(), true));
                    }
                    loop = segments;
                    loopBytes = 0;
                    for (long b : loop.values()) loopBytes += b;
                    Log.i("recording folder: " + loopBytes / 1_000_000 + " MB in " + loop.size()
                            + " segments (measured in " + took + " ms)");
                }
            }
        };
        t.setDaemon(true);
        t.start();
    }

    /** Deletes the oldest segments until the configured share of the drive is free. */
    private void keepFree() throws IOException {
        StatFs fs = new StatFs(dir.getPath());
        long want = fs.getTotalBytes() / 100 * config.minFreePercent;
        while (fs.getAvailableBytes() < want) {
            File[] segments = dir.listFiles(new FilenameFilter() {
                @Override
                public boolean accept(File d, String name) {
                    return name.endsWith(".ts");
                }
            });
            if (segments == null || segments.length == 0) {
                Log.w("drive is full and there are no segments left to delete");
                return;
            }
            Arrays.sort(segments);
            File oldest = segments[0];
            if (oldest.delete()) {
                Log.i("deleted " + oldest.getName() + " to make room");
                forget(oldest.getName());
            }
            else {
                Log.w("can't delete " + oldest);
                return;
            }
            fs.restat(dir.getPath());
        }
    }

    /** Takes a segment the save button moved into Saved out of the size limit's count. */
    synchronized void saved(String name) {
        if (current != null && current.getName().equals(name)) current = null;
        else forget(name);
    }

    /** Keeps the size limit's count right when keepFree deletes a segment. */
    private synchronized void forget(String name) {
        Long was = loop == null ? null : loop.remove(name);
        if (was != null) loopBytes -= was;
    }
}
