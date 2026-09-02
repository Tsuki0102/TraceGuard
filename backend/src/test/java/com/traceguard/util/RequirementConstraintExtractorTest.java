package com.traceguard.util;

import com.traceguard.util.RequirementConstraintExtractor.ConstraintPoint;
import com.traceguard.util.RequirementConstraintExtractor.Kind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GAP-046：需求约束点提取与代码实现证据比对单元测试。
 */
@DisplayName("GAP-046 需求约束点提取与代码证据比对")
class RequirementConstraintExtractorTest {

    @Test
    @DisplayName("空需求/空代码不抛异常")
    void emptyInputs() {
        assertThat(RequirementConstraintExtractor.extract(null)).isEmpty();
        assertThat(RequirementConstraintExtractor.extract("")).isEmpty();
        assertThat(RequirementConstraintExtractor.constraintMatch(null, null)).isEqualTo(0.0);
        // FUN-13：无约束点需求（如"普通功能需求"）返回 -1，由调用方回退中性值（不再按 0.0 判定）
        assertThat(RequirementConstraintExtractor.constraintMatch("普通功能需求", "public void f(){}")).isEqualTo(-1.0);
        // 有约束点需求返回 [0,1]
        assertThat(RequirementConstraintExtractor.constraintMatch("金额必须大于0", "if (amount <= 0) throw new Exception();")).isBetween(0.0, 1.0);
    }

    @Test
    @DisplayName("提取各类约束点")
    void extractKinds() {
        List<ConstraintPoint> pts = RequirementConstraintExtractor.extract(
                "订单创建时必须校验金额大于0且客户ID不能为空，失败时返回错误信息并记录异常");
        assertThat(pts).isNotEmpty();
        assertThat(pts.stream().map(ConstraintPoint::getKind).collect(Collectors.toSet()))
                .contains(Kind.NUMERIC_THRESHOLD, Kind.NON_NULL, Kind.EXCEPTION_PATH);
    }

    @Test
    @DisplayName("通用状态词识别：显式状态域需求被识别为状态守卫")
    void genericStateWordRecognized() {
        // 附带发现修复：仅含"状态"通用词、无具体状态枚举的需求也应识别为 STATE_GUARD
        String req = "订单状态需在状态域内";
        List<ConstraintPoint> pts = RequirementConstraintExtractor.extract(req);
        assertThat(pts).isNotEmpty();
        assertThat(pts.stream().map(ConstraintPoint::getKind).collect(Collectors.toSet()))
                .contains(Kind.STATE_GUARD);

        // 英文通用词 state 同样命中
        assertThat(RequirementConstraintExtractor.extract("order state must be valid").stream()
                .map(ConstraintPoint::getKind).collect(Collectors.toSet()))
                .contains(Kind.STATE_GUARD);
    }

    @Test
    @DisplayName("代码证据：实现约束点 vs 未实现约束点")
    void implementedDetection() {
        // 需求只要求异常处理，代码实现 try-catch -> 覆盖率 1.0
        String implementedCode = "public void process(){ try{ handle(); }catch(Exception e){ log(e); } }";
        double c1 = RequirementConstraintExtractor.constraintMatch(
                "订单处理失败时需返回错误信息并记录异常", implementedCode);
        assertThat(c1).isEqualTo(1.0);

        // 同一需求，代码完全无异常路径 -> 覆盖率 0.0
        String missingCode = "public void process(){ System.out.println(\"ok\"); }";
        double c2 = RequirementConstraintExtractor.constraintMatch(
                "订单处理失败时需返回错误信息并记录异常", missingCode);
        assertThat(c2).isEqualTo(0.0);
        assertThat(c1).isGreaterThan(c2);

        // 数值阈值：需求含金额上限，代码有数值比较 -> 命中
        assertThat(RequirementConstraintExtractor.implemented(Kind.NUMERIC_THRESHOLD,
                "public void f(BigDecimal amount){ if(amount.compareTo(BigDecimal.ZERO) > 0) return true; }")).isTrue();
        assertThat(RequirementConstraintExtractor.implemented(Kind.NUMERIC_THRESHOLD,
                "public void f(){ System.out.println(\"hi\"); }")).isFalse();

        // 状态守卫：需求要求状态校验，代码有 status 比较 + 拒绝逻辑 -> 命中
        assertThat(RequirementConstraintExtractor.implemented(Kind.STATE_GUARD,
                "if (!order.getStatus().equals(Order.STATUS_PENDING)) { throw new IllegalStateException(); }")).isTrue();
        assertThat(RequirementConstraintExtractor.implemented(Kind.STATE_GUARD,
                "System.out.println(status);")).isFalse();

        // 幂等：需求要求去重窗口，代码有缓存 + 时间窗口 -> 命中
        assertThat(RequirementConstraintExtractor.implemented(Kind.IDEMPOTENT,
                "LocalDateTime last = cache.get(key); if (Duration.between(last, now).getSeconds() < 60) throw ...;")).isTrue();
        assertThat(RequirementConstraintExtractor.implemented(Kind.IDEMPOTENT,
                "int x = 1;")).isFalse();

        // 限流：需求要求每分钟最多N条，代码有计数 + 时间窗口 -> 命中
        assertThat(RequirementConstraintExtractor.implemented(Kind.RATE_LIMIT,
                "timestamps.removeIf(t -> Duration.between(t, now).getSeconds() > 60); if (timestamps.size() >= MAX_LIMIT) throw new RateLimitException(\"rate limit exceeded\");")).isTrue();

        // 库存：需求要求先检查库存，代码有库存比较 -> 命中
        assertThat(RequirementConstraintExtractor.implemented(Kind.STOCK_CHECK,
                "if (stock < quantity) throw new IllegalStateException(\"库存不足\");")).isTrue();
        assertThat(RequirementConstraintExtractor.implemented(Kind.STOCK_CHECK,
                "System.out.println(\"ok\");")).isFalse();
    }

    @Test
    @DisplayName("P0-1：CodeProfile 预扫描路径与字符串入口结果逐位一致")
    void profiledPathParityWithStringPath() {
        // 覆盖：无约束点/数值阈值/参数校验/状态/库存/幂等/中文业务规则/英文需求/空代码 等形态
        String[] reqs = {
                "普通功能需求",
                "金额必须大于0",
                "相同用户15分钟内最多登录5次，超出抛异常",
                "订单状态必须为PENDING或PAID才能退款，且退款金额不得超过原金额",
                "userId不能为空，channel必须合法",
                "下单前需校验库存，库存不足返回失败",
                "接口需幂等，重复提交使用同一窗口时间戳去重",
                "学生考试时长最多120分钟，及格分数60分",
                "the notification content must not be null",
                "system should notify user when refund failed",
                null,
                ""
        };
        String[] codes = {
                "public void f(){}",
                "if (amount <= 0) throw new Exception();",
                "if (now - last.getTime() > 5 * 60 * 1000L) { count = 0; } if (++count > 5) throw new LimitException();",
                "if (order.getStatus() == PENDING || order.getStatus() == PAID) { refund(amount); }",
                "if (userId == null) throw new IllegalArgumentException();",
                "if (stock < quantity) throw new IllegalStateException(\"库存不足\");",
                "String key = requestId; long window = Duration.ofSeconds(30).toMillis(); cache.put(key, window);",
                "public void submit(){ if (duration <= 0 || duration > 120) throw ...; }",
                "if (content == null || content.isEmpty()) return;",
                "public void onFail(){ notifyOwner(\"refund failed\"); }",
                null,
                "public void g(){ System.out.println(1); }"
        };
        for (int i = 0; i < reqs.length; i++) {
            String r = reqs[i];
            String c = codes[i];
            double viaString = RequirementConstraintExtractor.constraintMatch(r, c);
            double viaProfile = RequirementConstraintExtractor.constraintMatchProfiled(r,
                    RequirementConstraintExtractor.CodeProfile.of(c));
            assertThat(viaProfile)
                    .withFailMessage("parity failed: req=%s code=%s viaString=%s viaProfile=%s", r, c, viaString, viaProfile)
                    .isEqualTo(viaString);
        }
    }
}
