package com.traceguard.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.traceguard.entity.AuditLog;
import com.traceguard.mapper.AuditLogMapper;
import com.traceguard.util.HashChainUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审计服务层单测（4.10 缺口补齐）：验证哈希链整链校验编排逻辑（AUD-08，FR-PLAT-004 防篡改）。
 * 纯 Mockito，不依赖 Spring 上下文。
 */
class AuditServiceTest {

    private AuditService auditService;
    private AuditLogMapper auditLogMapper;

    @BeforeEach
    void setUp() {
        auditService = new AuditService();
        auditLogMapper = Mockito.mock(AuditLogMapper.class);
        ReflectionTestUtils.setField(auditService, "auditLogMapper", auditLogMapper);
    }

    /** 构建一条完整的哈希链（prevHash 串联），返回可注入 mapper 的列表 */
    private List<AuditLog> buildChain(int n) {
        List<AuditLog> logs = new ArrayList<>();
        String prev = HashChainUtil.GENESIS;
        LocalDateTime t = LocalDateTime.of(2026, 1, 1, 0, 0);
        for (int i = 0; i < n; i++) {
            AuditLog log = new AuditLog();
            log.setId((long) (i + 1));
            log.setUserId(1L);
            log.setUsername("user" + i);
            log.setOperation("op" + i);
            log.setMethod("POST");
            log.setPath("/api/x");
            log.setIp("127.0.0.1");
            log.setStatusCode(200);
            log.setCostMs(10L);
            log.setSuccess(1);
            log.setCreateTime(t.plusMinutes(i));
            log.setPrevHash(prev);
            log.setCurHash(HashChainUtil.computeCurHash(log, prev));
            logs.add(log);
            prev = log.getCurHash();
        }
        return logs;
    }

    @Test
    @DisplayName("整链完整时 verifyChain 返回 valid=true 且无断点")
    void verifyChainAllValid() {
        List<AuditLog> chain = buildChain(5);
        Mockito.when(auditLogMapper.selectList(Mockito.any(LambdaQueryWrapper.class))).thenReturn(chain);

        var result = auditService.verifyChain();
        assertThat(result.get("valid")).isEqualTo(true);
        assertThat(result.get("total")).isEqualTo(5);
        assertThat(result.get("firstBrokenIndex")).isEqualTo(-1);
    }

    @Test
    @DisplayName("篡改中间记录业务字段后 verifyChain 定位到首条断点")
    void verifyChainDetectsTamper() {
        List<AuditLog> chain = buildChain(5);
        // 篡改第 3 条（index=2）的 operation，但不重算 curHash
        chain.get(2).setOperation("malicious-op");
        Mockito.when(auditLogMapper.selectList(Mockito.any(LambdaQueryWrapper.class))).thenReturn(chain);

        var result = auditService.verifyChain();
        assertThat(result.get("valid")).isEqualTo(false);
        assertThat(result.get("firstBrokenIndex")).isEqualTo(2);
        assertThat(result.get("firstBrokenId")).isEqualTo(3L);
    }

    @Test
    @DisplayName("空表时 verifyChain 返回 valid=true 且 total=0")
    void verifyChainEmptyTable() {
        Mockito.when(auditLogMapper.selectList(Mockito.any(LambdaQueryWrapper.class))).thenReturn(new ArrayList<>());
        var result = auditService.verifyChain();
        assertThat(result.get("valid")).isEqualTo(true);
        assertThat(result.get("total")).isEqualTo(0);
    }
}
