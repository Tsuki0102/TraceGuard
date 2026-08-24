import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * GAP-008：性能基准工程生成器（一次性 main 程序）。
 * 按参数化模板批量展开纯 JDK 依赖的 Java 工程 + 配套需求文档，供 PerformanceTest 分阶段计时。
 *
 * 用法：
 *   java BenchmarkProjectGenerator <输出目录> <类数> <需求条数>
 *   例：java BenchmarkProjectGenerator ../kilo 12 30
 *       java BenchmarkProjectGenerator ../tenk 100 80
 *
 * 生成内容：
 *   - code/com/sample/bench/ServiceXX.java（CRUD/校验/状态流转/循环聚合 模式化方法）
 *   - requirements.txt（编号需求条目，与生成方法集合对应）
 */
public class BenchmarkProjectGenerator {

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.out.println("用法: BenchmarkProjectGenerator <输出目录> <类数> <需求条数>");
            return;
        }
        Path outDir = Path.of(args[0]).toAbsolutePath().normalize();
        int classCount = Integer.parseInt(args[1]);
        int reqCount = Integer.parseInt(args[2]);
        if (reqCount > classCount * 4) {
            reqCount = classCount * 4; // 需求条数上限 = 类数*4，保证与方法集合对应
        }

        Path codeDir = outDir.resolve("code").resolve("com").resolve("sample").resolve("bench");
        Files.createDirectories(codeDir);
        StringBuilder reqSb = new StringBuilder();
        reqSb.append("# 性能基准工程需求规格说明书\n");
        reqSb.append("# 版本: 1.0  生成器: BenchmarkProjectGenerator\n\n");

        int reqSeq = 0;
        long totalLines = 0;
        for (int i = 1; i <= classCount; i++) {
            String cls = "Service" + String.format("%03d", i);
            String entity = "Entity" + String.format("%03d", i);
            String clsCode = buildClass(cls, entity, i);
            Files.writeString(codeDir.resolve(cls + ".java"), clsCode, StandardCharsets.UTF_8);
            totalLines += clsCode.lines().count();

            // 每个类提供 4 条需求模板槽位（新增/查询/更新/状态流转），需求条数按上限截断
            String[] reqs = {
                    "REQ-" + String.format("%04d", ++reqSeq) + ": 支持" + entity + "的创建，创建时校验名称非空且金额大于0，否则返回错误。",
                    "REQ-" + String.format("%04d", ++reqSeq) + ": 支持按ID查询" + entity + "详情，查询不到时返回null。",
                    "REQ-" + String.format("%04d", ++reqSeq) + ": 支持更新" + entity + "，更新后状态由PENDING流转为PROCESSING，不允许跳过中间状态。",
                    "REQ-" + String.format("%04d", ++reqSeq) + ": 支持" + entity + "状态流转到COMPLETED，必须校验金额非负与总量守恒。"
            };
            for (String r : reqs) {
                if (reqSeq <= reqCount) {
                    reqSb.append(r).append("\n\n");
                }
            }
        }
        Files.writeString(outDir.resolve("requirements.txt"), reqSb.toString(), StandardCharsets.UTF_8);
        System.out.println("生成完成: " + outDir);
        System.out.println("  类数=" + classCount + ", 需求条数=" + Math.min(reqSeq, reqCount)
                + ", 代码行数≈" + totalLines);
    }

    /** 生成单个模式化类：CRUD/校验/状态流转/循环聚合/批量操作，纯 JDK 依赖（每类约 100 行） */
    private static String buildClass(String cls, String entity, int seed) {
        StringBuilder sb = new StringBuilder();
        sb.append("package com.sample.bench;\n\n");
        sb.append("import java.util.ArrayList;\n");
        sb.append("import java.util.HashMap;\n");
        sb.append("import java.util.List;\n");
        sb.append("import java.util.Map;\n\n");
        sb.append("/**\n");
        sb.append(" * 基准样例类 ").append(cls).append("：CRUD + 状态流转 + 聚合统计（模式化模板，仅供性能评测）\n");
        sb.append(" * 对应需求条目：REQ-").append(String.format("%04d", (seed - 1) * 4 + 1))
          .append(" ~ REQ-").append(String.format("%04d", (seed - 1) * 4 + 4)).append("\n");
        sb.append(" */\n");
        sb.append("public class ").append(cls).append(" {\n\n");
        sb.append("    public static final int MAX_AMOUNT = 1000000;\n");
        sb.append("    public static final int MAX_BATCH = 100;\n");
        sb.append("    private final Map<String, ").append(entity).append("> store = new HashMap<>();\n\n");

        // create
        sb.append("    /** 创建：校验名称非空且金额大于0 */\n");
        sb.append("    public ").append(entity).append(" create(String id, String name, double amount) {\n");
        sb.append("        if (name == null || name.trim().isEmpty()) {\n");
        sb.append("            throw new IllegalArgumentException(\"name must not be empty\");\n");
        sb.append("        }\n");
        sb.append("        if (amount <= 0 || amount > MAX_AMOUNT) {\n");
        sb.append("            throw new IllegalArgumentException(\"amount out of range\");\n");
        sb.append("        }\n");
        sb.append("        if (store.containsKey(id)) {\n");
        sb.append("            throw new IllegalStateException(\"duplicate id\");\n");
        sb.append("        }\n");
        sb.append("        ").append(entity).append(" e = new ").append(entity).append("(id, name, amount);\n");
        sb.append("        store.put(id, e);\n");
        sb.append("        return e;\n");
        sb.append("    }\n\n");

        // query
        sb.append("    /** 查询：按ID精确查询，未命中返回null */\n");
        sb.append("    public ").append(entity).append(" queryById(String id) {\n");
        sb.append("        return store.get(id);\n");
        sb.append("    }\n\n");

        // update + state transition
        sb.append("    /** 更新并流转状态：PENDING -> PROCESSING */\n");
        sb.append("    public boolean updateToProcessing(String id) {\n");
        sb.append("        ").append(entity).append(" e = store.get(id);\n");
        sb.append("        if (e == null || !\"PENDING\".equals(e.getStatus())) {\n");
        sb.append("            return false;\n");
        sb.append("        }\n");
        sb.append("        e.setStatus(\"PROCESSING\");\n");
        sb.append("        return true;\n");
        sb.append("    }\n\n");

        // complete with invariant checks
        sb.append("    /** 完成：校验金额非负后流转到 COMPLETED */\n");
        sb.append("    public boolean complete(String id) {\n");
        sb.append("        ").append(entity).append(" e = store.get(id);\n");
        sb.append("        if (e == null || e.getAmount() < 0) {\n");
        sb.append("            return false;\n");
        sb.append("        }\n");
        sb.append("        e.setStatus(\"COMPLETED\");\n");
        sb.append("        return true;\n");
        sb.append("    }\n\n");

        // list + aggregate
        sb.append("    /** 聚合：统计金额总和（循环聚合） */\n");
        sb.append("    public double sumAmounts() {\n");
        sb.append("        double sum = 0;\n");
        sb.append("        for (").append(entity).append(" e : store.values()) {\n");
        sb.append("            sum += e.getAmount();\n");
        sb.append("        }\n");
        sb.append("        return sum;\n");
        sb.append("    }\n\n");

        // list by status
        sb.append("    /** 按状态过滤查询 */\n");
        sb.append("    public List<").append(entity).append("> listByStatus(String status) {\n");
        sb.append("        List<").append(entity).append("> result = new ArrayList<>();\n");
        sb.append("        for (").append(entity).append(" e : store.values()) {\n");
        sb.append("            if (status.equals(e.getStatus())) {\n");
        sb.append("                result.add(e);\n");
        sb.append("            }\n");
        sb.append("        }\n");
        sb.append("        return result;\n");
        sb.append("    }\n\n");

        // batch create
        sb.append("    /** 批量创建：单次不超过 MAX_BATCH 条 */\n");
        sb.append("    public int batchCreate(List<").append(entity).append("> items) {\n");
        sb.append("        if (items == null || items.size() > MAX_BATCH) {\n");
        sb.append("            throw new IllegalArgumentException(\"batch size exceeded\");\n");
        sb.append("        }\n");
        sb.append("        int created = 0;\n");
        sb.append("        for (").append(entity).append(" item : items) {\n");
        sb.append("            if (item != null && !store.containsKey(item.getId())) {\n");
        sb.append("                store.put(item.getId(), item);\n");
        sb.append("                created++;\n");
        sb.append("            }\n");
        sb.append("        }\n");
        sb.append("        return created;\n");
        sb.append("    }\n\n");

        // state distribution
        sb.append("    /** 统计各状态数量分布 */\n");
        sb.append("    public Map<String, Integer> statusDistribution() {\n");
        sb.append("        Map<String, Integer> dist = new HashMap<>();\n");
        sb.append("        for (").append(entity).append(" e : store.values()) {\n");
        sb.append("            dist.merge(e.getStatus(), 1, Integer::sum);\n");
        sb.append("        }\n");
        sb.append("        return dist;\n");
        sb.append("    }\n\n");

        // validate amount list (sum constraint)
        sb.append("    /** 校验一组金额合计不超过上限（总量守恒） */\n");
        sb.append("    public boolean validateTotalAmount(List<Double> amounts) {\n");
        sb.append("        if (amounts == null) {\n");
        sb.append("            return false;\n");
        sb.append("        }\n");
        sb.append("        double total = 0;\n");
        sb.append("        for (Double a : amounts) {\n");
        sb.append("            if (a == null || a < 0) {\n");
        sb.append("                return false;\n");
        sb.append("            }\n");
        sb.append("            total += a;\n");
        sb.append("        }\n");
        sb.append("        return total <= MAX_AMOUNT;\n");
        sb.append("    }\n\n");

        // cancel: PENDING -> CANCELLED
        sb.append("    /** 取消：仅 PENDING 状态可取消 */\n");
        sb.append("    public boolean cancel(String id) {\n");
        sb.append("        ").append(entity).append(" e = store.get(id);\n");
        sb.append("        if (e == null || !\"PENDING\".equals(e.getStatus())) {\n");
        sb.append("            return false;\n");
        sb.append("        }\n");
        sb.append("        e.setStatus(\"CANCELLED\");\n");
        sb.append("        return true;\n");
        sb.append("    }\n\n");

        sb.append("    /** 内部实体：ID/名称/金额/状态 */\n");
        sb.append("    public static class ").append(entity).append(" {\n");
        sb.append("        private final String id;\n");
        sb.append("        private final String name;\n");
        sb.append("        private final double amount;\n");
        sb.append("        private String status = \"PENDING\";\n\n");
        sb.append("        public ").append(entity).append("(String id, String name, double amount) {\n");
        sb.append("            this.id = id;\n");
        sb.append("            this.name = name;\n");
        sb.append("            this.amount = amount;\n");
        sb.append("        }\n\n");
        sb.append("        public String getId() { return id; }\n");
        sb.append("        public String getName() { return name; }\n");
        sb.append("        public double getAmount() { return amount; }\n");
        sb.append("        public String getStatus() { return status; }\n");
        sb.append("        public void setStatus(String status) { this.status = status; }\n");
        sb.append("    }\n");
        sb.append("}\n");
        return sb.toString();
    }
}
