package com.altair.probe;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** 文件选择器也遵守网络下载的大小边界，复制失败不保留半包。 */
public final class ApkFiles {
    public static void copy(InputStream input, File target) throws IOException {
        copy(input, target, 64L * 1024 * 1024);
    }
    static void copy(InputStream input, File target, long limit) throws IOException {
        boolean complete = false;
        try {
            try (InputStream in = input; FileOutputStream out = new FileOutputStream(target)) {
                byte[] buffer = new byte[64 * 1024];
                long total = 0;
                for (int n; (n = in.read(buffer)) != -1;) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("复制已取消");
                    total += n;
                    if (total > limit) throw new IOException("APK 超过 64 MiB 大小上限");
                    out.write(buffer, 0, n);
                }
                if (total < 1000) throw new IOException("文件过小，不是完整 APK");
            }
            complete = true;
        } finally {
            if (!complete) target.delete();
        }
    }
}
