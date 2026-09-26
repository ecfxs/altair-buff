package com.altair.probe;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.function.IntConsumer;
import java.util.function.LongSupplier;

/** 更新下载的资源边界；跳转也必须使用 HTTPS，失败不保留半包。 */
public final class UpdateDownload {
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    private static final long TOTAL_MS = 120_000L;

    interface Connections { HttpURLConnection open(URL url) throws IOException; }

    public static void download(String address, File out, int connectMs, int readMs, IntConsumer progress)
            throws IOException {
        download(address, out, connectMs, readMs, progress,
                url -> (HttpURLConnection) url.openConnection(),
                () -> System.nanoTime() / 1_000_000, MAX_BYTES, TOTAL_MS);
    }

    static void download(String address, File out, int connectMs, int readMs, IntConsumer progress,
                         Connections connections, LongSupplier clock, long maxBytes, long totalMs)
            throws IOException {
        long deadline = clock.getAsLong() + totalMs;
        HttpURLConnection connection = null;
        boolean complete = false;
        try {
            URL url = new URL(address);
            for (int redirects = 0; ; redirects++) {
                if (!"https".equalsIgnoreCase(url.getProtocol())) throw new IOException("更新地址及跳转必须使用 HTTPS");
                connection = connections.open(url);
                connection.setConnectTimeout(timeout(connectMs, deadline, clock));
                connection.setReadTimeout(timeout(readMs, deadline, clock));
                connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);
                connection.setRequestProperty("User-Agent", "altair-probe");
                connection.setRequestProperty("Cache-Control", "no-cache, no-store");
                int code = connection.getResponseCode();
                timeout(readMs, deadline, clock);
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = connection.getHeaderField("Location");
                    if (redirects >= 5 || location == null) throw new IOException("更新源跳转过多或缺少目标地址");
                    url = new URL(url, location);
                    connection.disconnect();
                    connection = null;
                    continue;
                }
                if (code != 200) throw new IOException("HTTP " + code);
                long expected = connection.getContentLengthLong();
                if (expected > maxBytes) throw new IOException("更新包超过大小上限");
                try (InputStream in = connection.getInputStream(); FileOutputStream fos = new FileOutputStream(out)) {
                    byte[] buffer = new byte[64 * 1024];
                    long count = 0;
                    while (true) {
                        if (Thread.currentThread().isInterrupted()) throw new IOException("下载已取消");
                        connection.setReadTimeout(timeout(readMs, deadline, clock));
                        int n = in.read(buffer);
                        timeout(readMs, deadline, clock);
                        if (n < 0) break;
                        count += n;
                        if (count > maxBytes) throw new IOException("更新包超过大小上限");
                        fos.write(buffer, 0, n);
                        if (expected > 0) progress.accept((int) Math.min(100, count * 100 / expected));
                    }
                    if (count == 0 || (expected >= 0 && count != expected)) throw new IOException("更新包下载不完整");
                }
                complete = true;
                return;
            }
        } finally {
            if (connection != null) connection.disconnect();
            if (!complete && out.exists() && !out.delete()) out.deleteOnExit();
        }
    }

    private static int timeout(int requested, long deadline, LongSupplier clock) throws IOException {
        long remaining = deadline - clock.getAsLong();
        if (remaining <= 0) throw new IOException("更新下载超过总时限");
        return (int) Math.max(1, Math.min(requested, remaining));
    }
}
