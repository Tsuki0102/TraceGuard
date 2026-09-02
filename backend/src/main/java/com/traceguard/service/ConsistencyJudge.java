package com.traceguard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.traceguard.llm.LangChainAdapter;
import com.traceguard.llm.LlmCallExecutor;
import com.traceguard.llm.LlmChain;
import com.traceguard.llm.LlmMessage;
import com.traceguard.llm.LlmResponse;
import com.traceguard.llm.Stage;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AUD-02：LLM 语义判定（需求-代码一致性二审）。
 *
 * 在规则三维相似度（GAP-005）判定之后，若启用了大模型，对每个需求-代码对
 * 交由 LLM 结合需求原文与代码实现做语义级判定（是否一致 + GAP-020 四类缺陷主类型），
 * 用于纠正规则判定对"同主题但有行为缺陷"实现的漏检。
 *
 * 组件可独立于 Spring 使用（评测类直接构造 LlmCallExecutor 复用本类的静态方法）。
 */
@Slf4j
public class ConsistencyJudge {

    /** LLM 增强引擎：self=OpenAI 兼容客户端（default）；langchain=langchain4j 编排（GAP-044） */
    public enum Engine { SELF, LANGCHAIN }

    /**
     * 路径B（GAP-043/044 小模型兜底）：LLM 判不一致仅当规则综合相似度低于该阈值才覆盖规则判定；
     * 否则认为语义已高度相似、LLM 判缺陷大概率为误报，用规则判定兜底为一致。
     * 用于抑制 CodeLlama:7b 等小模型"默认一致守不住"导致的误报，不依赖模型理解复杂指令。
     * 注意：FUN-04b 双判定管线 {@link #judgeDual} 走共识/仲裁决策，不套用本闸门。
     */
    private static final double LLM_OVERRIDE_MAX_SIM = 0.70;

    /** FUN-04b：双判定分歧时的规则风险仲裁阈值（CodeDefectPatternDetector 风险分 >= 该值才允许判不一致） */
    static final double JUDGE_ARBITER_RISK_THRESHOLD = 0.30;

    /** FUN-04b：规则否决权阈值——量化边界确定性证据 >= 该值时，推翻"双判定一致=一致"的结论 */
    static final double QUANTIFY_VETO_THRESHOLD = 0.45;

    /**
     * P1-1：双判定并发线程池（daemon 常驻，2 线程并行执行变体 A/B）。
     * LlmCallExecutor.execute 自带信号量限流/熔断（线程安全），并发仅加速排队，
     * maxConcurrentCalls>=2 时单对判定耗时约 -50%；=1 时自动串行，无副作用。
     */
    private static final ExecutorService DUAL_JUDGE_POOL = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r);
        t.setName("judge-dual");
        t.setDaemon(true);
        return t;
    });

    /** FUN-04b：类级判定证据（分工清单 + 常量定义），由 ClassEvidenceScanner 扫描原始源码构造 */
    public static class JudgeContext {
        /** 同类方法签名（不含当前方法），用于"其他环节职责"归属 */
        public final List<String> siblingSignatures;
        /** 类级字段/静态常量声明行，用于量化数值核对与单点归因 */
        public final List<String> classConstants;

        public JudgeContext(List<String> siblingSignatures, List<String> classConstants) {
            this.siblingSignatures = siblingSignatures == null ? List.of() : siblingSignatures;
            this.classConstants = classConstants == null ? List.of() : classConstants;
        }

        public boolean isEmpty() {
            return siblingSignatures.isEmpty() && classConstants.isEmpty();
        }

        /**
         * 由 ClassEvidenceScanner 扫描出的混合行（字段/常量声明 + 方法签名）构造判定证据：
         * 含 static/final 的声明行归常量区；形如 "... name(args)" 的签名单归分工区（剔除当前方法自身）。
         */
        public static JudgeContext fromEvidence(List<String> scannedLines, String currentMethodName) {
            if (scannedLines == null || scannedLines.isEmpty()) {
                return new JudgeContext(List.of(), List.of());
            }
            List<String> consts = new ArrayList<>();
            List<String> siblings = new ArrayList<>();
            for (String raw : scannedLines) {
                if (raw == null || raw.isBlank()) continue;
                String s = raw.trim();
                boolean isConst = s.contains("static") && s.contains("final") && !s.endsWith(")");
                if (isConst) {
                    if (consts.size() < 15 && !consts.contains(s)) consts.add(s);
                    continue;
                }
                if (!s.contains("(")) continue;
                if (currentMethodName != null && isSignatureOf(s, currentMethodName)) continue;
                if (siblings.size() < 60 && !siblings.contains(s)) siblings.add(s);
            }
            return new JudgeContext(siblings, consts);
        }

        /** 判断签名行是否属于指定方法名（取 "(" 前最后一个标识符比较） */
        private static boolean isSignatureOf(String signatureLine, String methodName) {
            String head = signatureLine.split("\\(", 2)[0].trim();
            if (head.isEmpty()) return false;
            String[] parts = head.split("[\\s<>]");
            for (int i = parts.length - 1; i >= 0; i--) {
                if (!parts[i].isEmpty()) {
                    return parts[i].equals(methodName);
                }
            }
            return false;
        }
    }

    /** LLM 判定结果 */
    public static class Judgement {
        private final boolean consistent;
        private final String defectType;   // GAP-020 四类主类型（consistent=true 时为空）
        private final String reason;
        private final Engine engine;       // 实际生效的引擎（langchain 回退后记为 SELF）

        public Judgement(boolean consistent, String defectType, String reason, Engine engine) {
            this.consistent = consistent;
            this.defectType = defectType;
            this.reason = reason;
            this.engine = engine;
        }

        public boolean isConsistent() { return consistent; }
        public String getDefectType() { return defectType; }
        public String getReason() { return reason; }
        public Engine getEngine() { return engine; }
    }

    private final LlmCallExecutor executor;
    private final Engine engine;
    private final LangChainAdapter langChain;

    /** self 引擎构造器（默认） */
    public ConsistencyJudge(LlmCallExecutor executor) {
        this(executor, Engine.SELF, null);
    }

    /** 双引擎构造器：langchain 引擎需提供 adapter；adapter 不可用时自动降级 SELF */
    public ConsistencyJudge(LlmCallExecutor executor, Engine engine, LangChainAdapter langChain) {
        this.executor = executor;
        boolean lcUsable = engine == Engine.LANGCHAIN && langChain != null && langChain.isAvailable();
        this.engine = lcUsable ? Engine.LANGCHAIN : Engine.SELF;
        this.langChain = lcUsable ? langChain : null;
        if (engine == Engine.LANGCHAIN && !lcUsable) {
            log.warn("GAP-044：langchain 引擎不可用，已自动回退 self 引擎。");
        }
    }

    /**
     * 对单个需求-代码对执行 LLM 一致性判定。
     * 失败（LLM 未启用/调用失败/解析失败）返回 null，由调用方保留规则判定。
     * langchain 引擎调用异常时自动回退 self 引擎。
     */
    public Judgement judge(String requirementText, String codeSnippet,
                           double semanticSimilarity, double constraintMatch,
                           double invariantSatisfaction, double totalSimilarity,
                           String ruleDefectType) {
        if (executor == null) {
            return null;
        }
        String system = buildSystemPrompt();
        String user = buildUserPrompt(requirementText, codeSnippet, semanticSimilarity,
                constraintMatch, invariantSatisfaction, totalSimilarity);
        if (this.engine == Engine.LANGCHAIN && langChain != null) {
            try {
                String raw = langChain.generate(system, user);
                Judgement j = parse(raw);
                if (j != null) {
                    // 路径B（GAP-043/044 小模型兜底）：LLM 判不一致仅当规则相似度偏低才覆盖，
                    // 否则用规则相似度为小模型兜底，抑制 7B "默认一致守不住"的误报。
                    if (!j.isConsistent() || totalSimilarity < LLM_OVERRIDE_MAX_SIM) {
                        return new Judgement(j.isConsistent(), j.getDefectType(), j.getReason(), Engine.LANGCHAIN);
                    }
                    log.warn("GAP-043：LangChain 判不一致但 totalSimilarity={}≥{}，规则兜底判一致（抑制小模型误报）",
                            String.format("%.2f", totalSimilarity), LLM_OVERRIDE_MAX_SIM);
                    return new Judgement(true, "", j.getReason() + "(相似度兜底)", Engine.LANGCHAIN);
                }
                log.warn("GAP-044：langchain 返回解析失败，回退 self 引擎。");
            } catch (Exception e) {
                log.warn("GAP-044：langchain 引擎调用失败，回退 self 引擎：{}", e.getMessage());
            }
            // 回退 self
        }
        LlmResponse resp = executor.execute(Stage.CONSISTENCY_CHECK,
                List.of(LlmMessage.system(system), LlmMessage.user(user)));
        if (!resp.isSuccess() || resp.getContent() == null) {
            log.warn("GAP-049 judge LLM 调用失败/空响应：{}", resp.getContent());
            return null;
        }
        Judgement parsed = parse(resp.getContent());
        if (parsed == null && contentEmpty(resp.getContent())) {
            // 本地模型(Qwen/CodeLlama)偶发空 completion：重试一次以应对空响应，提升可用性
            log.warn("GAP-049 judge 空响应重试一次");
            LlmResponse retry = executor.execute(Stage.CONSISTENCY_CHECK,
                    List.of(LlmMessage.system(system), LlmMessage.user(user)));
            if (retry.isSuccess() && retry.getContent() != null) {
                parsed = parse(retry.getContent());
            }
        }
        if (parsed == null) {
            return null;
        }
        // 路径B：self 引擎同样受相似度闸门约束
        if (!parsed.isConsistent() && totalSimilarity >= LLM_OVERRIDE_MAX_SIM) {
            log.warn("GAP-043：self 引擎判不一致但 totalSimilarity={}≥{}，规则兜底判一致",
                    String.format("%.2f", totalSimilarity), LLM_OVERRIDE_MAX_SIM);
            return new Judgement(true, "", parsed.getReason() + "(相似度兜底)", Engine.SELF);
        }
        return new Judgement(parsed.isConsistent(), parsed.getDefectType(), parsed.getReason(), Engine.SELF);
    }

    /**
     * 判断 LLM 响应是否为空/空白（本地模型偶发空 completion 场景）
     */
    private static boolean contentEmpty(String c) {
        return c == null || c.trim().isEmpty();
    }

    // ==================== FUN-04b：双判定管线（交叉验证 + 规则仲裁 + 数值归属过滤） ====================
    //
    // 背景（2026-08-27 离线实验台 tools/llm-judge-lab，55 标注对 @qwen2.5-coder:14b temp=0）：
    //   - 单提示词+分工证据注入：漏检 0%，但误报 27.6%（"需求缺失/数值归因"型误报群）；
    //   - 精简准则+三示例：误报降至 10.3% 边缘，但召回回吐（漏检 ~15%）；
    //   - 融合策略：两套提示词各判一次 -> 一致则采纳；分歧时以规则风险分仲裁；
    //     数值类不一致再过确定性"归属过滤"。离线达标 acc=94.5%/miss=3.8%/fpr=6.9%（SRS 目标全过）。

    /** 变体A 增量准则（FUN-04b v1，与 lab prompts.SCOPE_ANCHOR_EXTRA 同源）：锚定方法职责 + 分工证据 + 数值核对 */
    private static final String ANCHOR_EXTRA_V1 =
            "\n【最小判定单元——最高优先级】判定对象是“这个方法”，不是“这条需求的全部”。一条需求通常由多个"
            + "方法分工实现：创建/修改/删除/查询各自是独立方法，阅卷/复核/申诉各是一个环节。当前方法只要实现了"
            + "它名字和签名所对应的那个分工点，即使需求里其他功能点在本方法中没有出现，也判 consistent=true。"
            + "禁止因为“需求还要求了别的功能”而把需求缺失(defectType=需求缺失)判给一个已实现自身职责的方法。\n"
            + "【拆分/委托证据】判断某义务是否由其他方法承担时，看：①下方《同类其他方法》分工清单中是否有名字/职责"
            + "对应的条目——有即视为该义务由那一环承担，与本方法无关；②本方法体内对辅助校验方法的调用视为该校验"
            + "已实现；③常量在同类中定义并被相应检查使用也算实现。多步工作流（如 提交申诉->教师审核->给出复核结果、"
            + "发送->重试->统计）每一步各是一个方法，不要因本方法未包含其他步骤而判缺失。\n"
            + "【显式数值逐项核对】需求中出现的每一个量化约束（如 每分钟最多10条、60秒窗口内、不超过500字符、"
            + "每页默认20条最大100条、最多重试3次），若该约束由本方法负责，必须在代码中找到相等的数值才允许判一致；"
            + "找不到、或数值不等（如定义了30秒而需求60秒），一律判不一致(业务逻辑不一致)。但分页条数、结果集字段等"
            + "展示类细节由列表/查询环节承担时不算本方法的缺陷；需求没有写的数值不要凭空对号。\n"
            + "【单点归因】类常量数值不符只归属于使用它执行比较检查的方法；清理缓存、进度查询、统计汇总等辅助方法"
            + "引用同一常量不算缺陷。\n"
            + "【约束校验的边界】仅当需求为某参数显式声明了取值/合法性规则（必须>0、不能为空、不得超过N、状态必须为X）"
            + "且该校验属于当前方法的入参入口职责时，缺失才构成不一致(约束条件不满足)；调用方保证完整性的对象字段、"
            + "防御性额外校验、需求未要求的格式检查均不算缺陷。\n"
            + "【前置校验被中断的情形】需求要求的拒绝式校验（如“必须验证…否则拒绝”）依赖前置取值；若代码因缺"
            + "少必要的 null 判断而在执行该校验前就会抛 NullPointerException，则等于没有完成该校验——这不算"
            + "健壮性问题，应判不一致(约束条件不满足)。只有当需求完全没有要求该校验/行为时，潜在 NPE 才忽略。\n";

    /** 变体B 增量准则（精简四条） */
    private static final String SLIM_ANCHOR_EXTRA =
            "\n【判定规则——只看四条】\n"
            + "1. 判定对象是当前方法自身的分工职责；需求中属于其他方法/环节的功能不算它的缺陷，"
            + "《同类其他方法》清单中有对应条目即为他人职责。\n"
            + "2. 需求的显式数值边界由本方法的比较逻辑负责时，实现数值必须与需求一致，否则判业务逻辑不一致。\n"
            + "3. 类常量数值不符只归属【用比较符使用它做检查】的方法；清理/查询/汇总等辅助方法引用同一常量不算缺陷。\n"
            + "4. 需求要求的拒绝式校验因缺少 null 判断而根本执行不到时，判约束条件不满足；其余健壮性问题忽略。\n";

    /** 变体B 结构化示例（归因/工作流/数值对照，虚构场景） */
    private static final String FEWSHOT_SLIM_EXTRA =
            "\n【示例A·常量归因】需求：“预览图片生成后300秒内有效”。类常量 PREVIEW_TTL_SECONDS = 180 被"
            + "checkExpiry() 用于过期判断；当前待判方法是 cleanupPreviews()（定时清理过期项）。——判定：TTL 数值不符"
            + "属于 checkExpiry 的缺陷；清理方法只是维护机制。输出 {\"consistent\":true,\"defectType\":\"\",\"reason\":\"TTL缺陷属checkExpiry\"}。\n"
            + "【示例C·工作流拆分】需求：“退款申请->客服审核->原路退回”。当前方法 submitRefundClaim() 仅创建申请单"
            + "并返回编号；同类清单含 auditRefund()/doTransfer()。——判定：受理环节自身已实现。"
            + "输出 {\"consistent\":true,\"defectType\":\"\",\"reason\":\"受理职责已实现\"}。\n"
            + "【示例D·数值不符（对照例）】需求：“相同用户15分钟内最多登录5次”。当前方法 checkLoginLimit() 使用常量"
            + " LOGIN_MAX_PER_QUARTER = 8 做次数上限判断。——判定：需求量化值与实现不符且该方法正是检查承担者。"
            + "输出 {\"consistent\":false,\"defectType\":\"业务逻辑不一致\",\"reason\":\"上限8与需求5不符\"}。\n"
            + "【示例E·聚合分工】需求：“系统自动判分客观题并汇总总分”。当前方法 sumScores(Map<String,Integer>) "
            + "只对各题明细得分求和；同类清单另有 gradeChoice()/judgeBoolean() 承担单题判分。——判定：求和是自动评分"
            + "链路的合法分工环节。输出 {\"consistent\":true,\"defectType\":\"\",\"reason\":\"求和属合法分工\"}。";

    /**
     * FUN-04b 双判定管线。
     *
     * 决策表：
     *   A/B 一致         -> 采纳共识；
     *   分歧             -> ruleRisk >= JUDGE_ARBITER_RISK_THRESHOLD 时采纳"不一致"侧（类型取不一致侧），
     *                       否则判一致（抑制小模型摇摆造成的误报）；
     *   最终为不一致     -> 数值归属硬过滤（ownerOverride）：若仅因类常量数值不符，而该方法体内对该常量
     *                       没有任何不等式比较，视为归属错误改判一致。
     *
     * 复用 similarity-gate 的说明：本管线自带共识+仲裁的防误报机制，不再叠加
     * {@link #LLM_OVERRIDE_MAX_SIM} 相似度闸门；单阶段 judge() 行为保持不变。
     *
     * @return null 表示管线不可用（两次调用均失败），由调用方保留规则判定
     */
    public Judgement judgeDual(String requirementText, String codeSnippet,
                               double semanticSimilarity, double constraintMatch,
                               double invariantSatisfaction, double totalSimilarity,
                               String ruleDefectType, JudgeContext ctx, double ruleRisk) {
        if (executor == null) {
            return null;
        }
        if (ctx == null || ctx.isEmpty()) {
            // 无类级证据时退化单阶段（保持旧行为）
            return judge(requirementText, codeSnippet, semanticSimilarity, constraintMatch,
                    invariantSatisfaction, totalSimilarity, ruleDefectType);
        }
        // P1-1：变体 A/B 提示词相互独立，并发执行使单对判定耗时减半（内部由 LlmCallExecutor 信号量限流）
        CompletableFuture<Judgement> fa = CompletableFuture.supplyAsync(() -> callOnce(
                buildMessagesV1(requirementText, codeSnippet, semanticSimilarity,
                        constraintMatch, invariantSatisfaction, totalSimilarity, ctx)), DUAL_JUDGE_POOL);
        CompletableFuture<Judgement> fb = CompletableFuture.supplyAsync(() -> callOnce(
                buildMessagesSlim(requirementText, codeSnippet, semanticSimilarity,
                        constraintMatch, invariantSatisfaction, totalSimilarity, ctx)), DUAL_JUDGE_POOL);
        Judgement a = fa.join();
        Judgement b = fb.join();
        if (a == null && b == null) {
            return null;
        }
        if (a == null || b == null) {
            return finalizeOne(a != null ? a : b, requirementText, codeSnippet, ctx);
        }
        boolean detected;
        String type;
        String reason;
        if (a.isConsistent() == b.isConsistent()) {
            detected = !a.isConsistent();
            type = detected ? pickType(a.getDefectType(), b.getDefectType()) : "";
            reason = pickReason(a, b);
        } else {
            Judgement dissent = a.isConsistent() ? b : a;   // 判"不一致"的一侧
            detected = ruleRisk >= JUDGE_ARBITER_RISK_THRESHOLD;
            type = detected ? dissent.getDefectType() : "";
            reason = detected
                    ? dissent.getReason() + "(分歧仲裁:risk=" + fmt2(ruleRisk) + ")"
                    : "(双判定分歧按一致保留)";
        }
        Judgement merged = new Judgement(!detected, type, reason,
                a.getEngine() != null ? a.getEngine() : Engine.SELF);
        // 规则否决权（FUN-04b）：模型共识判"一致"，但量化边界错配的确定性证据成立
        // （如需求 500 字符上限而方法内 length()>1000），以规则证据推翻一致结论。
        if (merged.isConsistent()) {
            double qRisk = com.traceguard.util.CodeDefectPatternDetector.quantitativeBoundRiskSignal(
                    requirementText, codeSnippet, ctx.classConstants);
            if (qRisk >= QUANTIFY_VETO_THRESHOLD) {
                merged = new Judgement(false, "业务逻辑不一致",
                        "(规则量化边界否决:risk=" + fmt2(qRisk) + ")", Engine.SELF);
            }
        }
        return finalizeOne(merged, requirementText, codeSnippet, ctx);
    }

    private static String fmt2(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    /** 取非空缺陷类型：优先 A；兼容解析正常但类型空的极端情况 */
    private static String pickType(String t1, String t2) {
        if (t1 != null && !t1.isEmpty()) return t1;
        return t2 == null ? "" : t2;
    }

    private static String pickReason(Judgement a, Judgement b) {
        String ra = a.getReason() == null ? "" : a.getReason();
        String rb = b.getReason() == null ? "" : b.getReason();
        return ra.equals(rb) ? ra : ra + "/" + rb;
    }

    /**
     * 数值归属硬过滤：verdict 判不一致 且 需求含显式数量边界 且 类常量定义值与之同量级不等，
     * 而本方法体引用了该常量名却没有对它做任何不等式比较 —— 属于"别人的检查"，改判一致。
     * （只为放松：绝不在过滤中把一致改成不一致。）
     */
    static Judgement ownerOverride(Judgement j, String requirementText, String codeBody, JudgeContext ctx) {
        if (j == null || j.isConsistent() || requirementText == null || codeBody == null) {
            return j;
        }
        for (String line : ctx.classConstants) {
            Matcher nm = NAME_VALUE_NUM.matcher(line);
            if (!nm.find()) continue;
            String name = nm.group(1);
            int declared;
            try {
                declared = Integer.parseInt(nm.group(2));
            } catch (NumberFormatException e) {
                continue;
            }
            if (!codeBody.contains(name)) continue;
            Pattern cmpPat = Pattern.compile("(^|[^\\w'])" + Pattern.quote(name)
                    + "\\s*[<>]=?|[<>]=?\\s*" + Pattern.quote(name) + "\\b");
            if (cmpPat.matcher(codeBody).find()) continue;   // 方法体确有比较 -> 本方法即检查承担者
            Matcher bd = QUANTIFIED_BOUND.matcher(requirementText);
            while (bd.find()) {
                String gs = bd.group(1) != null ? bd.group(1) : bd.group(3);
                if (gs == null) continue;
                try {
                    int bound = Integer.parseInt(gs);
                    int lo = Math.min(bound, declared), hi = Math.max(bound, declared);
                    if (bound != declared && hi <= 5L * lo) {
                        return new Judgement(true, "", "(数值归属修正:" + name + ")",
                                j.getEngine());
                    }
                } catch (NumberFormatException ignored) {
                    // 忽略异常数值
                }
            }
        }
        return j;
    }

    private static final Pattern NAME_VALUE_NUM =
            Pattern.compile("(\\w+)\\s*=\\s*(-?\\d+)");
    private static final Pattern CMP_ON_CONST =
            Pattern.compile("(^|[^\\w'])CONSTNAME\\s*[<>]=?|[<>]=?\\s*CONSTNAME\\b");
    private static final Pattern QUANTIFIED_BOUND =
            Pattern.compile("(\\d{1,6})\\s*(秒|分钟|min|second|条|次|字符|个字|分)|"
                    + "(?:最多|不低于|至少|不超过|上限|窗口)[^\\d]{0,6}(\\d{1,6})",
                    Pattern.CASE_INSENSITIVE);

    /** 统一出口：数值归属过滤 + 引擎标注 */
    private static Judgement finalizeOne(Judgement j, String requirementText, String codeBody, JudgeContext ctx) {
        if (j == null) {
            return null;
        }
        if (codeBody == null || ctx == null || ctx.isEmpty()) {
            return new Judgement(j.isConsistent(), j.getDefectType(), j.getReason(), Engine.SELF);
        }
        // 直接逐条迭代常量清单（不能拼接成单串再交正则：首个 name=value 命中会吞掉后续常量的检查机会）
        JudgeContext constOnly = new JudgeContext(List.of(), ctx.classConstants);
        Judgement filtered = ownerOverride(j, requirementText, codeBody, constOnly);
        return new Judgement(filtered.isConsistent(), filtered.getDefectType(),
                filtered.getReason(), Engine.SELF);
    }

    /** 组装变体A消息：基础提示词 + 锚定准则 + 分工/常量证据区 */
    public List<LlmMessage> buildMessagesV1(String requirementText, String codeSnippet,
                                            double sem, double con, double inv, double total,
                                            JudgeContext ctx) {
        String system = buildSystemPrompt().replace("【输出】", ANCHOR_EXTRA_V1 + "\n【输出】");
        return List.of(LlmMessage.system(system),
                LlmMessage.user(buildEvidenceUserPrompt(requirementText, codeSnippet, sem, con, inv, total, ctx)));
    }

    /** 组装变体B消息：基础提示词 + 精简四条 + 三示例 */
    public List<LlmMessage> buildMessagesSlim(String requirementText, String codeSnippet,
                                              double sem, double con, double inv, double total,
                                              JudgeContext ctx) {
        String system = buildSystemPrompt()
                .replace("【输出】", SLIM_ANCHOR_EXTRA + FEWSHOT_SLIM_EXTRA + "\n【输出】");
        return List.of(LlmMessage.system(system),
                LlmMessage.user(buildEvidenceUserPrompt(requirementText, codeSnippet, sem, con, inv, total, ctx)));
    }

    /** 用户提示词：需求 + 代码 + 类级证据区（常量/分工清单）+ 得分参考 */
    private String buildEvidenceUserPrompt(String requirementText, String codeSnippet,
                                           double sem, double con, double inv, double total,
                                           JudgeContext ctx) {
        StringBuilder user = new StringBuilder();
        user.append("需求：").append(requirementText == null ? "" : requirementText).append("\n\n");
        user.append("代码：\n").append(truncate(codeSnippet, 2500)).append("\n\n");
        String evidence = evidenceSection(ctx);
        if (!evidence.isEmpty()) {
            user.append(evidence);
        }
        user.append("得分参考：语义=").append(fmt2(sem))
                .append(" 约束=").append(fmt2(con))
                .append(" 不变量=").append(fmt2(inv))
                .append(" 综合=").append(fmt2(total));
        return user.toString();
    }

    /** 由 JudgeContext 组装《同类字段/常量定义》与《同类其他方法》区块（插入到得分参考之前） */
    private static String evidenceSection(JudgeContext ctx) {
        if (ctx == null || ctx.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (!ctx.classConstants.isEmpty()) {
            sb.append("【同类字段/常量定义（数值核对证据）】\n");
            ctx.classConstants.stream().limit(15)
                    .forEach(s -> sb.append("- ").append(s).append('\n'));
            sb.append('\n');
        }
        if (!ctx.siblingSignatures.isEmpty()) {
            sb.append("【同类其他方法（分工清单，判定他人职责的依据）】\n");
            ctx.siblingSignatures.stream().limit(60)
                    .forEach(s -> sb.append("- ").append(s).append('\n'));
            sb.append('\n');
        }
        return sb.toString();
    }

    private Judgement callOnce(List<LlmMessage> messages) {
        try {
            LlmResponse resp = executor.execute(Stage.CONSISTENCY_CHECK, messages);
            if (!resp.isSuccess() || resp.getContent() == null) {
                return null;
            }
            return parse(resp.getContent());
        } catch (Exception e) {
            log.warn("judgeDual 调用失败：{}", e.getMessage());
            return null;
        }
    }


    /** 构造判定 prompt（静态，供评测复用；prompt 精简以保证单次生成快速） */
    public static List<LlmMessage> buildMessages(String requirementText, String codeSnippet,
                                                 double semanticSimilarity, double constraintMatch,
                                                 double invariantSatisfaction, double totalSimilarity,
                                                 String ruleDefectType) {
        return List.of(
                LlmMessage.system(buildSystemPrompt()),
                LlmMessage.user(buildUserPrompt(requirementText, codeSnippet, semanticSimilarity,
                        constraintMatch, invariantSatisfaction, totalSimilarity)));
    }

    private static String buildSystemPrompt() {
        // 注意：不向 LLM 传递规则判定结论（ruleDefectType），避免在默认阈值偏低时把
        // 一致性对误导向"缺陷"；仅提供分项得分作为中性上下文，要求 LLM 独立复核。
        // 2026-08-23 强化"方法职责边界"原则：需求条目可能覆盖整个系统的多个功能点，
        // 而当前代码仅是其中一个方法——仅当当前方法自身承担的职责未实现/不符时才判缺陷，
        // 避免把需求中其他方法/其他环节的功能缺失误判给当前方法（qwen-max 10/55 误报根因）。
        return "你是需求-代码一致性审查专家，判定维度是需求-代码一致性而非代码质量。\n"
                + "【输入范围】你拿到的是【一条需求条目】与【一个代码方法】的对，不是全系统对照。"
                + "需求条目可能描述整个系统的多个功能点，但当前代码只是其中一个方法。\n"
                + "【判定原则】默认一致(consistent=true)。仅当【当前代码方法自身承担的职责】未实现，"
                + "或明确违反需求中【由该方法负责】的约束/状态流转/业务规则（含需求数值与代码常量不一致、"
                + "比较方向反转）时判不一致。\n"
                + "【职责边界——重要】需求中属于其他方法/其他环节的功能（如'创建订单后自动发送通知'中的"
                + "发送通知、'下单后扣减库存'中的扣库存），只要不是当前方法承担的部分，即使当前需求条目"
                + "提到了它、代码其他位置也没有，也【不判给当前方法】；当前方法已实现其自身职责即为一致。\n"
                + "【其他约束】NPE、资源泄漏等健壮性问题除非需求要求防御，否则不算缺陷；"
                + "需求未显式要求的具体校验（如分值/长度范围、额外的状态流转步骤）不判为缺陷。\n"
                + "【类型】判不一致时按缺失点选四类主类型之一："
                + "①约束条件不满足=功能已实现但缺少/未完整实现需求要求【该方法承担】的参数·边界·取值校验"
                + "（如金额>0、长度≤N、批量≤N、必填非空等校验缺失或校验值不符）；"
                + "②业务逻辑不一致=功能已实现但业务规则·阈值·状态流转·数值·比较方向与需求不符"
                + "（如比较/判断方向反转、窗口或阈值与需求不一致、状态未按需求流转、规则不完整）；"
                + "③需求缺失=需求声明的功能或方法整体未实现/未覆盖；"
                + "④代码超范围实现=实现需求之外的功能。\n"
                + "【输出】严格只输出JSON（不要markdown围栏）：{\"consistent\":true或false,\"defectType\":"
                + "\"业务逻辑不一致|约束条件不满足|需求缺失|代码超范围实现\",\"reason\":\"≤20字\"}，"
                + "defectType仅在不一致时填四类之一。";
    }

    private static String buildUserPrompt(String requirementText, String codeSnippet,
                                           double semanticSimilarity, double constraintMatch,
                                           double invariantSatisfaction, double totalSimilarity) {
        StringBuilder user = new StringBuilder();
        user.append("需求：").append(requirementText == null ? "" : requirementText).append("\n\n");
        user.append("代码：\n").append(truncate(codeSnippet, 2500)).append("\n\n");
        user.append("得分参考：语义=").append(String.format("%.2f", semanticSimilarity))
                .append(" 约束=").append(String.format("%.2f", constraintMatch))
                .append(" 不变量=").append(String.format("%.2f", invariantSatisfaction))
                .append(" 综合=").append(String.format("%.2f", totalSimilarity));
        return user.toString();
    }

    /** 解析 LLM 响应为 Judgement；非法响应返回 null（触发降级保留规则判定） */
    public static Judgement parse(String content) {
        JsonNode node = LlmChain.parseJson(content);
        if (node == null) {
            log.warn("judge 响应解析失败（非 JSON），原始响应前 300 字符：{}",
                    content == null ? "null" : content.substring(0, Math.min(300, content.length())));
            return null;
        }
        boolean consistent = node.path("consistent").asBoolean(true);
        String type = normalizeType(node.path("defectType").asText(""));
        String reason = node.path("reason").asText("");
        if (!consistent && type.isEmpty()) {
            // LLM 判不一致但未给出类型 -> 视为解析失败，保留规则判定
            log.warn("judge 判不一致但缺陷类型为空，视为解析失败，原始响应前 200 字符：{}",
                    content == null ? "null" : content.substring(0, Math.min(200, content.length())));
            return null;
        }
        return new Judgement(consistent, type, reason, Engine.SELF);
    }

    /** 将 LLM 输出的缺陷类型规范化到 GAP-020 四类主类型 */
    static String normalizeType(String t) {
        if (t == null) {
            return "";
        }
        if (t.contains("需求缺失")) {
            return "需求缺失";
        }
        if (t.contains("代码超范围") || t.contains("超范围实现") || t.contains("超范围")) {
            return "代码超范围实现";
        }
        if (t.contains("约束") || t.contains("不变量") || t.contains("校验")
                || t.contains("空") || t.contains("异常") || t.contains("参数")) {
            return "约束条件不满足";
        }
        if (t.contains("业务逻辑") || t.contains("逻辑") || t.contains("行为") || t.contains("实现")) {
            return "业务逻辑不一致";
        }
        return "";
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
