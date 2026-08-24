package com.traceguard.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LlmConfigCryptoUtil 单元测试（GAP-021：tg_llm_config.api_key 加密存储）
 */
@DisplayName("LLM 配置加解密工具单元测试")
class LlmConfigCryptoUtilTest {

    @Test
    @DisplayName("AES-GCM 加解密往返一致")
    void encryptDecryptRoundTrip() throws Exception {
        String plain = "sk-0123456789abcdef";
        String cipher = LlmConfigCryptoUtil.encrypt(plain, "my-secret-key");
        assertThat(cipher).isNotEqualTo(plain);
        assertThat(LlmConfigCryptoUtil.isEncrypted(cipher)).isTrue();
        assertThat(LlmConfigCryptoUtil.decrypt(cipher, "my-secret-key")).isEqualTo(plain);
    }

    @Test
    @DisplayName("同一明文两次加密密文不同（随机 IV）")
    void randomIv() throws Exception {
        String c1 = LlmConfigCryptoUtil.encrypt("same", "k");
        String c2 = LlmConfigCryptoUtil.encrypt("same", "k");
        assertThat(c1).isNotEqualTo(c2);
        assertThat(LlmConfigCryptoUtil.decrypt(c1, "k")).isEqualTo("same");
        assertThat(LlmConfigCryptoUtil.decrypt(c2, "k")).isEqualTo("same");
    }

    @Test
    @DisplayName("错误密钥解密抛异常")
    void wrongKeyThrows() throws Exception {
        String cipher = LlmConfigCryptoUtil.encrypt("secret", "key-a");
        assertThatThrownBy(() -> LlmConfigCryptoUtil.decrypt(cipher, "key-b"))
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("空/普通文本不被判定为加密文本")
    void notEncryptedDetection() {
        assertThat(LlmConfigCryptoUtil.isEncrypted(null)).isFalse();
        assertThat(LlmConfigCryptoUtil.isEncrypted("")).isFalse();
        assertThat(LlmConfigCryptoUtil.isEncrypted("{\"a\":1}")).isFalse();
    }
}
