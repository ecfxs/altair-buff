package com.altair.probe;

import java.util.Locale;
import java.util.Set;

/** Pure signer trust policy shared by Android update validation and offline regression tests. */
public final class ApkSignerTrust {
    private ApkSignerTrust() { }

    public static boolean accepts(String expectedSha256, Set<String> actualSha256Digests) {
        if (expectedSha256 == null || expectedSha256.trim().isEmpty() || actualSha256Digests == null) return false;
        String expected = expectedSha256.replace(":", "").trim().toUpperCase(Locale.US);
        // 尚未启用密钥轮换策略；只能接受唯一且完全匹配的当前签名。
        if (!expected.matches("[0-9A-F]{64}") || actualSha256Digests.size() != 1) return false;
        for (String actual : actualSha256Digests) {
            if (actual != null && expected.equals(actual.replace(":", "").trim().toUpperCase(Locale.US))) {
                return true;
            }
        }
        return false;
    }
}
