package app.pub.builder

import android.system.ErrnoException
import android.system.Os
import java.io.File

/**
 * Identifies one version of a file: AtomicFile replaces the inode on every write, and an in-place
 * change alters the size or the nanosecond modification time. Stores use it to reuse what they last
 * validated instead of rereading and revalidating a large file before every operation.
 */
internal data class FileSignature(val inode: Long, val size: Long, val modifiedSeconds: Long, val modifiedNanos: Long) {
    companion object {
        /** Null when the file is missing, or when an AtomicFile backup means its state must be reread. */
        fun of(file: File): FileSignature? {
            if (File(file.path + ".bak").exists()) return null
            return try {
                val stat = Os.stat(file.path)
                FileSignature(stat.st_ino, stat.st_size, stat.st_mtim.tv_sec, stat.st_mtim.tv_nsec)
            } catch (_: ErrnoException) { null }
        }
    }
}
