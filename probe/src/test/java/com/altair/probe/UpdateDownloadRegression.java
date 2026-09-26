package com.altair.probe;

import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicLong;

/** 不联网，使用真实下载器与可控响应验证资源边界。 */
public final class UpdateDownloadRegression {
    static final class Response extends HttpURLConnection {
        byte[] body = new byte[] {1, 2, 3};
        long length = 3;
        int status = 200;
        String location;
        AtomicLong clock;
        Response() throws Exception { super(new URL("https://updates.example/app.apk")); }
        public void disconnect() { }
        public boolean usingProxy() { return false; }
        public void connect() { }
        public int getResponseCode() { return status; }
        public long getContentLengthLong() { return length; }
        public String getHeaderField(String name) { return location; }
        public InputStream getInputStream() {
            return new ByteArrayInputStream(body) {
                public synchronized int read(byte[] b, int off, int len) {
                    if (clock != null) clock.addAndGet(101);
                    return super.read(b, off, len);
                }
            };
        }
    }
    public static void run() throws Exception {
        File out = Files.createTempFile("altair-download-test-", ".apk").toFile();
        try {
            Response good = new Response();
            UpdateDownload.download("https://updates.example/app.apk", out, 20, 20, p -> {},
                url -> good, () -> 0L, 4, 100);
            if (out.length() != 3) throw new AssertionError("完整响应应保存");
            for (int scenario = 0; scenario < 6; scenario++) {
                Response response = new Response();
                AtomicLong clock = new AtomicLong();
                if (scenario == 0) response.length = 5; // 预先拒绝超大包
                if (scenario == 1) { response.length = -1; response.body = new byte[5]; }
                if (scenario == 2) response.length = 4; // 截断响应
                if (scenario == 3) response.clock = clock; // 持续有数据也不能绕过总时限
                if (scenario >= 4) {
                    response.status = 302;
                    response.location = scenario == 4 ? "http://updates.example/app.apk" : "/loop";
                }
                try {
                    UpdateDownload.download("https://updates.example/app.apk", out, 20, 20, p -> {},
                        url -> response, clock::get, 4, 100);
                    throw new AssertionError("应拒绝危险下载场景 " + scenario);
                } catch (IOException expected) {
                    if (out.exists()) throw new AssertionError("失败后不能保留旧包或半包");
                }
            }
        } finally { Files.deleteIfExists(out.toPath()); }
    }
}
