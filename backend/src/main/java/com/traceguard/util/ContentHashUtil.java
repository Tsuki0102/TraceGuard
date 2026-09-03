package com.traceguard.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * P2-5：内容哈希工具（增量分析的基础设施）。
 * 对代码文本/文件内容计算 sha256 十六进制摘要，用于判断"该文件自上次分析以来是否变更"。
 */
public final class ContentHashUtil {

    private ContentHashUtil() {}

    /** 计算文本的 sha256 十六进制摘要；null/空返回空串（调用方按"未参与解析"处理） */
    public static String sha256(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        return sha256(content.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
