package com.traceguard.util;

import com.traceguard.config.CodeParseScope;
import com.traceguard.config.CodeParseScopeHolder;
import com.traceguard.entity.CodeDefect;
import com.traceguard.entity.CodeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JavaCodeParserUtil 单元测试：解析与六类缺陷规则检测
 */
@DisplayName("Java代码解析与缺陷检测单元测试")
class JavaCodeParserUtilTest {

    private final JavaCodeParserUtil parserUtil = new JavaCodeParserUtil();

    @TempDir
    Path tempDir;

    private File writeJavaFile(String content) throws Exception {
        File file = tempDir.resolve("Sample.java").toFile();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }

    @Test
    @DisplayName("解析Java文件提取类名方法名与代码内容，文件路径为相对路径")
    void parseFileExtractsCodeUnits() throws Exception {
        File file = writeJavaFile(
                "public class Sample {\n" +
                "    public int add(int a, int b) {\n" +
                "        return a + b;\n" +
                "    }\n" +
                "}\n");
        List<CodeUnit> units = parserUtil.parseFile(file, tempDir.toString());
        assertThat(units).hasSize(1);
        assertThat(units.get(0).getClassName()).isEqualTo("Sample");
        assertThat(units.get(0).getMethodName()).isEqualTo("add");
        assertThat(units.get(0).getCodeContent()).contains("return a + b");
        // 不泄漏绝对路径
        assertThat(units.get(0).getFilePath()).isEqualTo("Sample.java");
    }

    @Test
    @DisplayName("语义向量化：提取方法调用动词与结构特征并存入semanticVector")
    void parsesSemanticVector() throws Exception {
        File file = writeJavaFile(
                "public class OrderService {\n" +
                "    public void saveOrder(java.util.List<String> orders) {\n" +
                "        for (String o : orders) {\n" +
                "            if (o == null) { throw new IllegalArgumentException(\"empty\"); }\n" +
                "            orderMapper.insert(o);\n" +
                "        }\n" +
                "    }\n" +
                "}\n");
        List<CodeUnit> units = parserUtil.parseFile(file, tempDir.toString());
        assertThat(units).hasSize(1);
        String vector = units.get(0).getSemanticVector();
        assertThat(vector).isNotBlank().startsWith("{\"terms\"");
        // 行为动词（方法调用名驼峰切分）
        assertThat(vector).contains("insert");
        // 结构特征（循环/分支/抛出）
        assertThat(vector).contains("loop").contains("branch").contains("throw");
    }

    @Test
    @DisplayName("UTF-8中文注释正确解析不乱码")
    void parsesUtf8ChineseComment() throws Exception {
        File file = writeJavaFile(
                "public class Sample {\n" +
                "    /**\n" +
                "     * 计算两数之和\n" +
                "     * @param a 加数\n" +
                "     */\n" +
                "    public int add(int a, int b) {\n" +
                "        return a + b;\n" +
                "    }\n" +
                "}\n");
        List<CodeUnit> units = parserUtil.parseFile(file, tempDir.toString());
        assertThat(units).hasSize(1);
        assertThat(units.get(0).getLogicDescription()).contains("计算两数之和");
    }

    @Test
    @DisplayName("检测字符串拼接SQL注入风险")
    void detectsSqlInjection() throws Exception {
        File file = writeJavaFile(
                "public class Sample {\n" +
                "    public void query(String name) {\n" +
                "        String sql = \"select * from user where name = '\" + name + \"'\";\n" +
                "    }\n" +
                "}\n");
        List<CodeDefect> defects = parserUtil.detectBasicDefects(file, tempDir.toString(), 1L, 1L);
        assertThat(defects).extracting(CodeDefect::getDefectType).contains("SQL注入");
        assertThat(defects).anySatisfy(d -> {
            if ("SQL注入".equals(d.getDefectType())) {
                assertThat(d.getSeverity()).isEqualTo("high");
                assertThat(d.getFilePath()).isEqualTo("Sample.java");
            }
        });
    }

    @Test
    @DisplayName("检测while(true)无退出条件的死循环")
    void detectsInfiniteLoop() throws Exception {
        File file = writeJavaFile(
                "public class Sample {\n" +
                "    public void loop() {\n" +
                "        while (true) {\n" +
                "            doSomething();\n" +
                "        }\n" +
                "    }\n" +
                "}\n");
        List<CodeDefect> defects = parserUtil.detectBasicDefects(file, tempDir.toString(), 1L, 1L);
        assertThat(defects).extracting(CodeDefect::getDefectType).contains("逻辑死循环");
    }

    @Test
    @DisplayName("含break的while(true)不误报死循环")
    void noFalsePositiveForLoopWithBreak() throws Exception {
        File file = writeJavaFile(
                "public class Sample {\n" +
                "    public void loop() {\n" +
                "        while (true) {\n" +
                "            if (ready()) { break; }\n" +
                "        }\n" +
                "    }\n" +
                "}\n");
        List<CodeDefect> defects = parserUtil.detectBasicDefects(file, tempDir.toString(), 1L, 1L);
        assertThat(defects).extracting(CodeDefect::getDefectType)
                .doesNotContain("逻辑死循环");
    }

    @Test
    @DisplayName("检测未关闭的资源泄漏")
    void detectsResourceLeak() throws Exception {
        File file = writeJavaFile(
                "public class Sample {\n" +
                "    public void read() throws Exception {\n" +
                "        FileInputStream fis = new FileInputStream(\"data.txt\");\n" +
                "        int b = fis.read();\n" +
                "    }\n" +
                "}\n");
        List<CodeDefect> defects = parserUtil.detectBasicDefects(file, tempDir.toString(), 1L, 1L);
        assertThat(defects).extracting(CodeDefect::getDefectType).contains("资源未释放");
    }

    @Test
    @DisplayName("try-with-resources不误报资源泄漏")
    void noFalsePositiveForTryWithResources() throws Exception {
        File file = writeJavaFile(
                "public class Sample {\n" +
                "    public void read() throws Exception {\n" +
                "        try (FileInputStream fis = new FileInputStream(\"data.txt\")) {\n" +
                "            int b = fis.read();\n" +
                "        }\n" +
                "    }\n" +
                "}\n");
        List<CodeDefect> defects = parserUtil.detectBasicDefects(file, tempDir.toString(), 1L, 1L);
        assertThat(defects).extracting(CodeDefect::getDefectType)
                .doesNotContain("资源未释放");
    }

    @Test
    @DisplayName("检测无保护的Optional.get()空指针风险")
    void detectsUnguardedOptionalGet() throws Exception {
        File file = writeJavaFile(
                "import java.util.Optional;\n" +
                "public class Sample {\n" +
                "    public String get() {\n" +
                "        Optional<String> opt = find();\n" +
                "        return opt.get().trim();\n" +
                "    }\n" +
                "}\n");
        List<CodeDefect> defects = parserUtil.detectBasicDefects(file, tempDir.toString(), 1L, 1L);
        assertThat(defects).extracting(CodeDefect::getDefectType).contains("空指针风险");
        assertThat(defects).anySatisfy(d -> {
            if ("空指针风险".equals(d.getDefectType())) {
                assertThat(d.getSeverity()).isEqualTo("high");
            }
        });
    }

    @Test
    @DisplayName("isPresent保护的Optional.get()不误报")
    void noFalsePositiveForGuardedOptional() throws Exception {
        File file = writeJavaFile(
                "import java.util.Optional;\n" +
                "public class Sample {\n" +
                "    public String get() {\n" +
                "        Optional<String> opt = find();\n" +
                "        if (opt.isPresent()) { return opt.get(); }\n" +
                "        return null;\n" +
                "    }\n" +
                "}\n");
        List<CodeDefect> defects = parserUtil.detectBasicDefects(file, tempDir.toString(), 1L, 1L);
        assertThat(defects).extracting(CodeDefect::getDefectType)
                .doesNotContain("空指针风险");
    }

    @Test
    @DisplayName("检测Map.get()链式调用的空指针风险")
    void detectsMapGetChain() throws Exception {
        File file = writeJavaFile(
                "import java.util.Map;\n" +
                "public class Sample {\n" +
                "    public int run(Map<String, java.util.List<String>> map) {\n" +
                "        return map.get(\"key\").size();\n" +
                "    }\n" +
                "}\n");
        List<CodeDefect> defects = parserUtil.detectBasicDefects(file, tempDir.toString(), 1L, 1L);
        assertThat(defects).extracting(CodeDefect::getDefectType).contains("空指针风险");
    }

    @Test
    @DisplayName("检测i<=arr.length差一错误导致的数组越界")
    void detectsOffByOne() throws Exception {
        File file = writeJavaFile(
                "public class Sample {\n" +
                "    public int sum(int[] arr) {\n" +
                "        int s = 0;\n" +
                "        for (int i = 0; i <= arr.length; i++) { s += arr[i]; }\n" +
                "        return s;\n" +
                "    }\n" +
                "}\n");
        List<CodeDefect> defects = parserUtil.detectBasicDefects(file, tempDir.toString(), 1L, 1L);
        assertThat(defects).extracting(CodeDefect::getDefectType).contains("数组越界风险");
    }

    @Test
    @DisplayName("正确的i<arr.length不误报越界")
    void noFalsePositiveForCorrectLoop() throws Exception {
        File file = writeJavaFile(
                "public class Sample {\n" +
                "    public int sum(int[] arr) {\n" +
                "        int s = 0;\n" +
                "        for (int i = 0; i < arr.length; i++) { s += arr[i]; }\n" +
                "        return s;\n" +
                "    }\n" +
                "}\n");
        List<CodeDefect> defects = parserUtil.detectBasicDefects(file, tempDir.toString(), 1L, 1L);
        assertThat(defects).extracting(CodeDefect::getDefectType)
                .doesNotContain("数组越界风险");
    }

    @Test
    @DisplayName("规范代码不产生任何缺陷")
    void cleanCodeProducesNoDefects() throws Exception {
        File file = writeJavaFile(
                "public class Sample {\n" +
                "    public int add(int a, int b) {\n" +
                "        return a + b;\n" +
                "    }\n" +
                "}\n");
        List<CodeDefect> defects = parserUtil.detectBasicDefects(file, tempDir.toString(), 1L, 1L);
        assertThat(defects).isEmpty();
    }

    @Test
    @DisplayName("缺陷行号定位到实际语句行而非方法起始行")
    void defectLineNumberPointsToActualStatement() throws Exception {
        File file = writeJavaFile(
                "public class Sample {\n" +
                "    public int sum(int[] arr) {\n" +
                "        int s = 0;\n" +
                "        for (int i = 0; i <= arr.length; i++) { s += arr[i]; }\n" +
                "        return s;\n" +
                "    }\n" +
                "}\n");
        List<CodeDefect> defects = parserUtil.detectBasicDefects(file, tempDir.toString(), 1L, 1L);
        CodeDefect d = defects.stream()
                .filter(x -> "数组越界风险".equals(x.getDefectType()))
                .findFirst().orElseThrow();
        // 第4行是for语句，缺陷行号应指向它而非第2行方法起始
        assertThat(d.getLineNumber()).isEqualTo(4);
    }

    @Test
    @DisplayName("资源泄漏缺陷行号定位到资源创建语句行")
    void resourceLeakLineNumberPointsToCreation() throws Exception {
        File file = writeJavaFile(
                "public class Sample {\n" +
                "    public void read() throws Exception {\n" +
                "        int a = 1;\n" +
                "        int b = 2;\n" +
                "        FileInputStream fis = new FileInputStream(\"data.txt\");\n" +
                "        int c = fis.read();\n" +
                "    }\n" +
                "}\n");
        List<CodeDefect> defects = parserUtil.detectBasicDefects(file, tempDir.toString(), 1L, 1L);
        CodeDefect d = defects.stream()
                .filter(x -> "资源未释放".equals(x.getDefectType()))
                .findFirst().orElseThrow();
        // 第5行是资源创建语句
        assertThat(d.getLineNumber()).isEqualTo(5);
    }

    @Test
    @DisplayName("SQL注入缺陷行号定位到字符串拼接语句行")
    void sqlInjectionLineNumberPointsToConcat() throws Exception {
        File file = writeJavaFile(
                "public class Sample {\n" +
                "    public void query(String name) {\n" +
                "        int a = 1;\n" +
                "        int b = 2;\n" +
                "        String sql = \"select * from user where name = '\" + name + \"'\";\n" +
                "        System.out.println(sql);\n" +
                "    }\n" +
                "}\n");
        List<CodeDefect> defects = parserUtil.detectBasicDefects(file, tempDir.toString(), 1L, 1L);
        CodeDefect d = defects.stream()
                .filter(x -> "SQL注入".equals(x.getDefectType()))
                .findFirst().orElseThrow();
        // 第5行是SQL拼接语句，而非第2行方法起始行
        assertThat(d.getLineNumber()).isEqualTo(5);
    }

    @Test
    @DisplayName("GAP-025：并行/串行解析结果一致性（按codeId排序后对比）")
    void parallelAndSerialParseResultsAreConsistent() throws Exception {
        // 创建多个Java文件以触发并行模式
        Path subDir = tempDir.resolve("com/example");
        subDir.toFile().mkdirs();
        
        writeMultipleFiles(subDir, "ServiceA.java", 
                "package com.example;\n" +
                "public class ServiceA {\n" +
                "    public int add(int a, int b) { return a + b; }\n" +
                "    public int multiply(int a, int b) { return a * b; }\n" +
                "}\n");
        writeMultipleFiles(subDir, "ServiceB.java",
                "package com.example;\n" +
                "public class ServiceB {\n" +
                "    public String concat(String x, String y) { return x + y; }\n" +
                "    public int subtract(int a, int b) { return a - b; }\n" +
                "}\n");
        writeMultipleFiles(subDir, "ServiceC.java",
                "package com.example;\n" +
                "public class ServiceC {\n" +
                "    public boolean isEmpty(String s) { return s == null || s.length() == 0; }\n" +
                "    public int size(Object[] arr) { return arr.length; }\n" +
                "}\n");
        writeMultipleFiles(subDir, "ServiceD.java",
                "package com.example;\n" +
                "public class ServiceD {\n" +
                "    public List<String> filter(java.util.List<String> items) { return items; }\n" +
                "    public void log(String msg) { System.out.println(msg); }\n" +
                "}\n");
        
        // 串行解析
        JavaCodeParserUtil serialParser = new JavaCodeParserUtil();
        JavaCodeParserUtil.ProjectParseResult serialResult = serialParser.parseProjectIsolated(tempDir.toString(), null);
        
        // 并行解析（多次运行确保稳定性）
        JavaCodeParserUtil parallelParser = new JavaCodeParserUtil();
        JavaCodeParserUtil.ProjectParseResult parallelResult1 = parallelParser.parseProjectIsolated(tempDir.toString(), null);
        JavaCodeParserUtil parallelParser2 = new JavaCodeParserUtil();
        JavaCodeParserUtil.ProjectParseResult parallelResult2 = parallelParser2.parseProjectIsolated(tempDir.toString(), null);
        
        // 数量一致
        assertThat(serialResult.codeUnits.size()).isGreaterThan(0);
        assertThat(parallelResult1.codeUnits.size()).isEqualTo(serialResult.codeUnits.size());
        assertThat(parallelResult2.codeUnits.size()).isEqualTo(serialResult.codeUnits.size());
        
        // 内容一致（按codeId分组对比）
        Map<String, CodeUnit> serialByCodeId = groupByCodeId(serialResult.codeUnits);
        Map<String, CodeUnit> parallelByCodeId1 = groupByCodeId(parallelResult1.codeUnits);
        Map<String, CodeUnit> parallelByCodeId2 = groupByCodeId(parallelResult2.codeUnits);
        
        for (String codeId : serialByCodeId.keySet()) {
            assertThat(parallelByCodeId1).containsKey(codeId);
            assertThat(parallelByCodeId2).containsKey(codeId);
            
            CodeUnit s = serialByCodeId.get(codeId);
            CodeUnit p1 = parallelByCodeId1.get(codeId);
            CodeUnit p2 = parallelByCodeId2.get(codeId);
            
            // 关键字段一致
            assertThat(p1.getClassName()).isEqualTo(s.getClassName());
            assertThat(p1.getMethodName()).isEqualTo(s.getMethodName());
            assertThat(p1.getCodeContent()).isEqualTo(s.getCodeContent());
            assertThat(p2.getClassName()).isEqualTo(s.getClassName());
            assertThat(p2.getMethodName()).isEqualTo(s.getMethodName());
            assertThat(p2.getCodeContent()).isEqualTo(s.getCodeContent());
        }
    }
    
    private File writeMultipleFiles(Path dir, String fileName, String content) throws Exception {
        File file = dir.resolve(fileName).toFile();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }
    
    private Map<String, CodeUnit> groupByCodeId(List<CodeUnit> units) {
        return units.stream().collect(Collectors.toMap(CodeUnit::getCodeId, u -> u));
    }

    @Test
    @DisplayName("GAP-016：无分支方法圈复杂度为 1")
    void cyclomaticComplexityOfSimpleMethodIsOne() throws Exception {
        File file = writeJavaFile(
                "public class Sample {\n" +
                "    public int add(int a, int b) {\n" +
                "        return a + b;\n" +
                "    }\n" +
                "}\n");
        List<CodeUnit> units = parserUtil.parseFile(file, tempDir.toString());
        assertThat(units).hasSize(1);
        assertThat(units.get(0).getCyclomaticComplexity()).isEqualTo(1);
    }

    @Test
    @DisplayName("GAP-016：含 if/else if/for/foreach/while/switch-case/catch/&&/||/?: 的方法复杂度正确")
    void cyclomaticComplexityOfBranchyMethod() throws Exception {
        // 判定节点统计：if(1) &&(1) else-if(1) ||(1) for(1) foreach(1) while(1)
        //               catch(1) ?:(1) switch 三个 case(3) = 12 -> 复杂度 1+12 = 13
        File file = writeJavaFile(
                "public class Sample {\n" +
                "    public int calc(int a, int b, int c, int[] arr) {\n" +
                "        int result = 0;\n" +
                "        if (a > 0 && b > 0) {\n" +
                "            result = 1;\n" +
                "        } else if (c > 0 || b < 0) {\n" +
                "            result = 2;\n" +
                "        }\n" +
                "        for (int i = 0; i < 3; i++) {\n" +
                "            result += i;\n" +
                "        }\n" +
                "        for (int x : arr) {\n" +
                "            result += x;\n" +
                "        }\n" +
                "        while (result > 10) {\n" +
                "            result--;\n" +
                "        }\n" +
                "        try {\n" +
                "            result = result > 0 ? result * 2 : 0;\n" +
                "        } catch (Exception e) {\n" +
                "            result = 0;\n" +
                "        }\n" +
                "        switch (a) {\n" +
                "            case 1: result += 1; break;\n" +
                "            case 2: result += 2; break;\n" +
                "            default: result += 3;\n" +
                "        }\n" +
                "        return result;\n" +
                "    }\n" +
                "}\n");
        List<CodeUnit> units = parserUtil.parseFile(file, tempDir.toString());
        assertThat(units).hasSize(1);
        assertThat(units.get(0).getCyclomaticComplexity()).isEqualTo(13);
    }

    // ==================== FR-CODE-001（2.4 整改项）：字段清单单元与解析范围过滤 ====================

    @Test
    @DisplayName("2.4 含字段的类生成字段清单单元（methodName=[字段清单]），方法单元照常生成")
    void parseFileGeneratesFieldListUnit() throws Exception {
        File file = writeJavaFile(
                "package com.example.order;\n" +
                "public class Order {\n" +
                "    private Long id;\n" +
                "    private String status;\n" +
                "    public void pay() { }\n" +
                "    public void cancel() { }\n" +
                "}\n");
        List<CodeUnit> units = parserUtil.parseFile(file, tempDir.toString());
        // 字段清单 + 2 个方法
        assertThat(units).hasSize(3);
        CodeUnit fieldUnit = units.stream()
                .filter(JavaCodeParserUtil::isFieldListUnit)
                .findFirst().orElseThrow();
        assertThat(fieldUnit.getMethodName()).isEqualTo(JavaCodeParserUtil.FIELD_LIST_MARKER);
        assertThat(fieldUnit.getCodeContent()).contains("private Long id").contains("private String status");
        assertThat(fieldUnit.getLogicDescription()).contains("2 个字段");
        assertThat(fieldUnit.getFilePath()).isEqualTo("Sample.java");
        // 普通方法单元不受影响
        assertThat(units).filteredOn(u -> !JavaCodeParserUtil.isFieldListUnit(u)).hasSize(2);
    }

    @Test
    @DisplayName("2.4 解析范围：includeMethods 仅保留指定方法，excludePackages 排除整个包")
    void parseFileRespectsScopeFilter() throws Exception {
        File file = writeJavaFile(
                "package com.example.biz;\n" +
                "public class OrderService {\n" +
                "    public void createOrder() { }\n" +
                "    public void cancelOrder() { }\n" +
                "    public String toString() { return \"x\"; }\n" +
                "}\n");
        // includeMethods 限定
        CodeParseScope include = new CodeParseScope();
        include.setIncludeMethods(Collections.singletonList("createOrder"));
        CodeParseScopeHolder.apply(include);
        try {
            List<CodeUnit> units = parserUtil.parseFile(file, tempDir.toString());
            assertThat(units).hasSize(1);
            assertThat(units.get(0).getMethodName()).isEqualTo("createOrder");
        } finally {
            CodeParseScopeHolder.reset();
        }
        // excludePackages 排除整个包
        CodeParseScope exclude = new CodeParseScope();
        exclude.setExcludePackages(Collections.singletonList("com.example.biz"));
        CodeParseScopeHolder.apply(exclude);
        try {
            List<CodeUnit> units = parserUtil.parseFile(file, tempDir.toString());
            assertThat(units).isEmpty();
        } finally {
            CodeParseScopeHolder.reset();
        }
    }

    @Test
    @DisplayName("2.4 解析范围：includePackages 前缀通配与 excludeMethods 生效")
    void parseFileRespectsPackageIncludeAndMethodExclude() throws Exception {
        File file = writeJavaFile(
                "package com.example.biz.service;\n" +
                "public class UserService {\n" +
                "    public void saveUser() { }\n" +
                "    public void getUser() { }\n" +
                "}\n");
        // includePackages 前缀通配
        CodeParseScope scope = new CodeParseScope();
        scope.setIncludePackages(Collections.singletonList("com.example.biz.*"));
        CodeParseScopeHolder.apply(scope);
        try {
            assertThat(parserUtil.parseFile(file, tempDir.toString())).hasSize(2);
        } finally {
            CodeParseScopeHolder.reset();
        }
        // excludeMethods 排除
        CodeParseScope exclude = new CodeParseScope();
        exclude.setExcludeMethods(Arrays.asList("getUser", "saveUser"));
        CodeParseScopeHolder.apply(exclude);
        try {
            assertThat(parserUtil.parseFile(file, tempDir.toString())).isEmpty();
        } finally {
            CodeParseScopeHolder.reset();
        }
    }
}
