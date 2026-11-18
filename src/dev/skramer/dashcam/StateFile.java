package dev.skramer.dashcam;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import libcore.io.Libcore;
import libcore.io.StructStat;

/**
 * Files in the state directory. That directory is shared with the root service scripts, so it's
 * world-writable (sticky: other uids can't delete or replace our files). Another app could still
 * put a symlink there under a name we haven't written yet, so nothing is written by opening its
 * final name: each write goes to a new temporary file, then is renamed over the name, which
 * replaces a link instead of following it. Reads that matter check the file is ours.
 */
final class StateFile {
    private StateFile() {}

    /** Replaces file with data; readable by every uid if readable (the root scripts and the app). */
    static void write(File file, byte[] data, boolean readable) throws IOException {
        File tmp = File.createTempFile("." + file.getName() + "-", ".tmp", file.getParentFile());
        try {
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(data);
            }
            if (readable) tmp.setReadable(true, false);
            if (!tmp.renameTo(file)) throw new IOException("can't replace " + file + " (owned by another uid?)");
            tmp = null;
        } finally {
            if (tmp != null) tmp.delete();
        }
    }

    /** True if file is a regular file (not a link) owned by this process's uid. */
    static boolean ours(File file) {
        try {
            StructStat s = Libcore.os.lstat(file.getPath());
            return (s.st_mode & 0170000) == 0100000 && s.st_uid == android.os.Process.myUid();
        } catch (Throwable t) {
            return false;
        }
    }
}
