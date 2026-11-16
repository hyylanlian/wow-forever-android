package app.gamenative.utils;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.io.FileWriter;

/**
 * 依赖下载缓存，放在共享存储（手机 Download 目录），这样卸载/重装 App 后缓存仍在，
 * 不必重新联网下载大文件（imagefs、dxvk、turnip、wincomponents 等）。
 *
 * filesDir 在卸载时会被清空，所以所有运行时下载的归档都应落在这里：
 *   /storage/emulated/0/Download/WoWForever/<sub>/
 * 若共享目录不可写（未授予"所有文件访问"权限），回退到 filesDir，保证功能不崩。
 */
public final class SharedCache {
    private static final String ROOT_DIR_NAME = "WoWForever";
    private static final String FALLBACK_DIR_NAME = ".sharedcache";

    private SharedCache() {}

    /** 共享缓存根目录；不可写时回退到私有 filesDir。 */
    public static File root(Context context) {
        try {
            File base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File dir = new File(base, ROOT_DIR_NAME);
            if (ensureWritable(dir)) return dir;
        } catch (Throwable ignored) {
            // fall through to private fallback
        }
        File fallback = new File(context.getFilesDir(), FALLBACK_DIR_NAME);
        //noinspection ResultOfMethodCallIgnored
        fallback.mkdirs();
        return fallback;
    }

    /** 共享缓存下的子目录（例如 "assets/dxwrapper"）。 */
    public static File dir(Context context, String sub) {
        return new File(root(context), sub);
    }

    /**
     * 一次性把旧位置（filesDir 内）的下载缓存搬到共享目录，避免升级到本版后还要重下一次。
     * 尽力而为：任何异常都吞掉，调用方可继续走正常下载流程。
     */
    public static void migrate(Context context) {
        try {
            File shared = root(context);
            File files = context.getFilesDir();
            if (shared.getAbsolutePath().equals(new File(files, FALLBACK_DIR_NAME).getAbsolutePath())) return;
            // 旧 downloader 缓存：filesDir/assets/<type>/ -> shared/assets/<type>/
            copyTree(new File(files, "assets"), new File(shared, "assets"));
            // 旧 imagefs 归档：filesDir/imagefs_*.txz -> shared/
            File[] top = files.listFiles();
            if (top != null) {
                for (File f : top) {
                    String n = f.getName();
                    if (f.isFile() && n.startsWith("imagefs_") && (n.endsWith(".txz") || n.endsWith(".tzst"))) {
                        File dst = new File(shared, n);
                        if (!dst.exists()) moveOrCopy(f, dst);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void copyTree(File src, File dst) {
        if (src == null || !src.isDirectory()) return;
        File[] kids = src.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            File d = new File(dst, k.getName());
            if (k.isDirectory()) {
                copyTree(k, d);
            } else if (k.isFile() && !(d.exists() && d.length() == k.length())) {
                moveOrCopy(k, d);
            }
        }
    }

    private static void moveOrCopy(File src, File dst) {
        try {
            File parent = dst.getParentFile();
            if (parent != null) parent.mkdirs();
            if (src.renameTo(dst)) return;
        } catch (Throwable ignored) {
        }
        try (java.io.FileInputStream in = new java.io.FileInputStream(src);
             java.io.FileOutputStream out = new java.io.FileOutputStream(dst)) {
            byte[] buf = new byte[1 << 20];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (Throwable ignored) {
        }
    }

    private static boolean ensureWritable(File dir) {
        try {
            if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) return false;
            File probe = new File(dir, ".probe");
            try (FileWriter w = new FileWriter(probe)) {
                w.write("1");
            }
            boolean ok = probe.isFile() && probe.length() > 0;
            //noinspection ResultOfMethodCallIgnored
            probe.delete();
            return ok;
        } catch (Throwable t) {
            return false;
        }
    }
}
