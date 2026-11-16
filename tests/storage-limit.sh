#!/bin/bash
# Checks the recorder's size limit on recordings (Storage.makeRoom with Config.maxBytes) on the computer: plain
# Java, with stand-ins for the few Android classes Storage, Config and Log use.
set -euo pipefail
repo=$(cd "$(dirname "$0")/.." && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/src/android/os" "$work/src/android/util"
cat > "$work/src/android/os/StatFs.java" <<'JAVA'
package android.os;
/** A drive with plenty of room, so only the size limit deletes. */
public class StatFs {
    public StatFs(String path) {}
    public void restat(String path) {}
    public long getTotalBytes() { return 1L << 40; }
    public long getAvailableBytes() { return 1L << 39; }
}
JAVA
cat > "$work/src/android/os/SystemClock.java" <<'JAVA'
package android.os;
public final class SystemClock {
    public static long uptimeMillis() { return System.nanoTime() / 1_000_000; }
}
JAVA
cat > "$work/src/android/util/Log.java" <<'JAVA'
package android.util;
public final class Log {
    public static int i(String tag, String msg) { return 0; }
    public static int w(String tag, String msg) { return 0; }
    public static int e(String tag, String msg, Throwable t) { return 0; }
}
JAVA
# Config checks the app's settings file with StateFile once that exists (it needs libcore): a stand-in.
cat > "$work/src/StateFile.java" <<'JAVA'
package dev.skramer.dashcam;
final class StateFile {
    static boolean ours(java.io.File f) { return f.isFile(); }
    static void write(java.io.File f, byte[] data, boolean readable) throws java.io.IOException {
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) { out.write(data); }
    }
}
JAVA
cat > "$work/src/StorageLimitTest.java" <<'JAVA'
package dev.skramer.dashcam;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
public final class StorageLimitTest {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static void write(File f, int bytes) throws IOException {
        f.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(f)) { out.write(new byte[bytes]); }
    }
    static String[] segments(File dir) {
        String[] s = dir.list((d, n) -> n.endsWith(".ts"));
        Arrays.sort(s);
        return s;
    }
    /** The first makeRoom starts measuring on another thread; this waits for it, then applies the limit. */
    static void settle(Storage s) throws Exception {
        s.makeRoom();
        Thread.sleep(300);
        s.makeRoom();
    }
    static Config config(File dir, long max) {
        Config c = Config.parse(new String[] {"--out=" + dir, "--bitrate=8", "--segment=10"});  // next segment: 11 bytes
        c.maxBytes = max;
        return c;
    }
    public static void main(String[] args) throws Exception {
        File root = new File(args[0]);

        // Ten 100-byte segments, at most 450: 4 fit (400 + 11 for the next). Saved clips and snapshots don't count.
        File dir = new File(root, "a");
        for (int i = 0; i < 10; i++) write(new File(dir, "20261101_00000" + i + ".ts"), 100);
        write(new File(dir, "Saved/20261031_000000.ts"), 1200);
        write(new File(dir, "Snapshots/x.jpg"), 300);
        Config ca = config(dir, 450);
        Storage s = new Storage(ca);
        settle(s);
        check(Arrays.equals(segments(dir), new String[] {"20261101_000006.ts", "20261101_000007.ts",
                "20261101_000008.ts", "20261101_000009.ts"}), "oldest go first: " + Arrays.toString(segments(dir)));
        check(new File(dir, "Saved/20261031_000000.ts").isFile() && new File(dir, "Snapshots/x.jpg").isFile(), "saved clips and snapshots stay");

        // Segments written from here on are counted without measuring again.
        File next = s.newSegment();
        write(next, 100);
        s.makeRoom();
        check(segments(dir).length == 4 && !new File(dir, "20261101_000006.ts").exists(), "a finished segment counts: " + Arrays.toString(segments(dir)));

        // A segment deleted in the app is skipped, not counted twice.
        check(new File(dir, "20261101_000007.ts").delete(), "test setup");
        next = s.newSegment();
        write(next, 100);
        s.makeRoom();
        check(segments(dir).length == 4, "a missing file frees its space: " + Arrays.toString(segments(dir)));

        // Saving moves segments out of the loop: that frees room in the limit.
        String[] before = segments(dir);
        for (int i = 2; i < 4; i++) {
            check(new File(dir, before[i]).renameTo(new File(dir, "Saved/" + before[i])), "test setup");
            s.saved(before[i]);
        }
        next = s.newSegment();
        write(next, 100);
        s.makeRoom();  // 8, 9 and the new one: 300 + 11, under 450
        check(segments(dir).length == 3, "saved segments free room: " + Arrays.toString(segments(dir)));
        ca.maxBytes = 250;
        s.makeRoom();  // down to 200 + 11
        check(segments(dir).length == 2 && !new File(dir, before[0]).exists(), "lowering the limit: " + Arrays.toString(segments(dir)));
        check(new File(dir, "Saved/" + before[2]).isFile() && new File(dir, "Saved/" + before[3]).isFile(), "saved segments are kept");

        // Raising the limit takes effect without deleting; no limit leaves everything.
        File b = new File(root, "b");
        for (int i = 0; i < 5; i++) write(new File(b, "20261102_00000" + i + ".ts"), 100);
        Config cb = config(b, 0);
        Storage sb = new Storage(cb);
        settle(sb);
        check(segments(b).length == 5, "no limit deletes nothing");
        cb.maxBytes = 320;
        settle(sb);
        check(segments(b).length == 3, "lowering the limit applies at the next segment: " + Arrays.toString(segments(b)));

        // Saved clips bigger than the limit change nothing.
        File c = new File(root, "c");
        for (int i = 0; i < 3; i++) write(new File(c, "20261103_00000" + i + ".ts"), 100);
        write(new File(c, "Saved/big.ts"), 1000);
        Storage sc = new Storage(config(c, 500));
        settle(sc);
        check(segments(c).length == 3, "saved clips don't count: " + Arrays.toString(segments(c)));

        // Settings: --max-gb and the app's maxgb= line, which wins; bad app values are ignored.
        check(Config.parse(new String[] {"--max-gb=64"}).maxBytes == 64_000_000_000L, "--max-gb");
        check(Config.parse(new String[] {"--max-gb=1.5"}).maxBytes == 1_500_000_000L, "decimal GB");
        check(Config.parse(new String[] {"--max-gb=0"}).maxBytes == 0, "0 is no limit");
        for (String bad : new String[] {"0.5", "-3", "NaN", "lots"}) {
            try {
                Config.parse(new String[] {"--max-gb=" + bad});
                check(false, "--max-gb=" + bad + " should be refused");
            } catch (IllegalArgumentException expected) { }
        }
        File conf = new File(root, "recorder.conf");
        try (FileOutputStream out = new FileOutputStream(conf)) { out.write("enabled=false\nmaxgb=32\n".getBytes("US-ASCII")); }
        Config app = Config.parse(new String[] {"--max-gb=64", "--app-conf=" + conf, "--state=" + root}).withAppSettings();
        check(app.maxBytes == 32_000_000_000L && !app.enabled, "the app's limit replaces --max-gb");
        try (FileOutputStream out = new FileOutputStream(conf)) { out.write("enabled=true\nmaxgb=0.2\n".getBytes("US-ASCII")); }
        app = Config.parse(new String[] {"--max-gb=64", "--app-conf=" + conf, "--state=" + root}).withAppSettings();
        check(app.maxBytes == 64_000_000_000L, "a bad app value is ignored");
        try (FileOutputStream out = new FileOutputStream(conf)) { out.write("enabled=true\n".getBytes("US-ASCII")); }
        app = Config.parse(new String[] {"--max-gb=64", "--app-conf=" + conf, "--state=" + root}).withAppSettings();
        check(app.maxBytes == 64_000_000_000L, "no app value keeps --max-gb");
        System.out.println("Storage limit: oldest first, saved kept and not counted, live changes and settings passed");
    }
}
JAVA
d="$repo/src/dev/skramer/dashcam"
javac -nowarn -d "$work/classes" "$work/src/android/os/"*.java "$work/src/android/util/"*.java \
    "$d/Storage.java" "$d/Config.java" "$d/Log.java" "$work/src/StateFile.java" "$work/src/StorageLimitTest.java"
mkdir "$work/drive"
java -cp "$work/classes" dev.skramer.dashcam.StorageLimitTest "$work/drive" > "$work/out.txt" || { cat "$work/out.txt"; exit 1; }
tail -1 "$work/out.txt"
