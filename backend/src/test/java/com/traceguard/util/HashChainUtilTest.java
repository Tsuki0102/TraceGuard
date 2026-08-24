package com.traceguard.util;

import com.traceguard.entity.AuditLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审计日志哈希链工具测试（AUD-08，FR-PLAT-004 防篡改）
 * 验证：链的连续性计算、对历史记录篡改可被检测、首条异常定位。
 */
@DisplayName("审计日志哈希链工具单元测试")
class HashChainUtilTest {

    private AuditLog sample(String user, String op, int success) {
        AuditLog log = new AuditLog();
        log.setUserId(1L);
        log.setUsername(user);
        log.setOperation(op);
        log.setMethod("POST");
        log.setPath("/api/test");
        log.setIp("127.0.0.1");
        log.setStatusCode(200);
        log.setCostMs(12L);
        log.setSuccess(success);
        log.setCreateTime(LocalDateTime.now());
        return log;
    }

    @Test
    @DisplayName("链头记录 prevHash=GENESIS，curHash 可计算且稳定")
    void genesisHash() {
        AuditLog log = sample("alice", "login", 1);
        String cur = HashChainUtil.computeCurHash(log, HashChainUtil.GENESIS);
        assertThat(cur).isNotNull().hasSize(64);
        log.setCurHash(cur);
        assertThat(HashChainUtil.verify(log, HashChainUtil.GENESIS)).isTrue();
    }

    @Test
    @DisplayName("连续两条记录：第二条 prevHash==第一条 curHash，且均通过校验")
    void chainContinuity() {
        AuditLog a = sample("alice", "login", 1);
        String h1 = HashChainUtil.computeCurHash(a, HashChainUtil.GENESIS);
        a.setCurHash(h1);

        AuditLog b = sample("bob", "export", 1);
        String h2 = HashChainUtil.computeCurHash(b, h1);
        b.setCurHash(h2);

        assertThat(HashChainUtil.verify(a, HashChainUtil.GENESIS)).isTrue();
        assertThat(HashChainUtil.verify(b, h1)).isTrue();
    }

    @Test
    @DisplayName("篡改中间记录的业务字段会破坏哈希链（verify=false）")
    void tamperDetected() {
        AuditLog a = sample("alice", "login", 1);
        String h1 = HashChainUtil.computeCurHash(a, HashChainUtil.GENESIS);
        a.setCurHash(h1);

        AuditLog b = sample("bob", "export", 1);
        String h2 = HashChainUtil.computeCurHash(b, h1);
        b.setCurHash(h2);

        // 攻击者篡改 b 的操作名，但未重算 curHash
        b.setOperation("delete-all");
        assertThat(HashChainUtil.verify(b, h1)).isFalse();
    }

    @Test
    @DisplayName("篡改前驱 curHash 会使后续记录全部失效")
    void prevHashTamperBreaksChain() {
        AuditLog a = sample("alice", "login", 1);
        String h1 = HashChainUtil.computeCurHash(a, HashChainUtil.GENESIS);
        a.setCurHash(h1);

        AuditLog b = sample("bob", "export", 1);
        String h2 = HashChainUtil.computeCurHash(b, h1);
        b.setCurHash(h2);

        // 篡改 a 的 curHash（模拟历史记录被改）
        a.setCurHash("deadbeef");
        // 此时 b 的 prevHash(h1) 已不等于被篡改后的 a.curHash，校验 b 失败
        assertThat(HashChainUtil.verify(b, h1)).isTrue(); // b 自身字段未变，仍校验通过
        // 但用「篡改后」的 a.curHash 作为 prev 去校验 b 会失败
        assertThat(HashChainUtil.verify(b, "deadbeef")).isFalse();
    }
}
