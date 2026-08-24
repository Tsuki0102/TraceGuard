package com.traceguard.util;

import com.traceguard.config.SootProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SootCfgBuilderUtil 单元测试（GAP-003 验证点 1/2）
 * 编译用例：JDK 标准库工程编译成功；含第三方 import 编译失败；
 * CFG 用例：try-catch 生成 catch 边、循环/分支节点正确、JSON 可解析。
 */
@DisplayName("Soot字节码级CFG构建器单元测试")
class SootCfgBuilderUtilTest {

    @TempDir
    Path tempDir;

    private SootProperties props() {
        SootProperties p = new SootProperties();
        p.setEnabled(true);
        p.setCompileTimeoutSeconds(60);
        p.setMethodCfgTimeoutSeconds(10);
        return p;
    }

    @Test
    @DisplayName("仅依赖JDK标准库的多类工程编译成功并产出class")
    void compileJdkOnlyProjectSucceeds() throws Exception {
        SootCfgBuilderUtil.configure(props());
        Path src = tempDir.resolve("src");
        Files.createDirectories(src.resolve("com/example"));
        Path svc = src.resolve("com/example/OrderService.java");
        Files.write(svc, List.of(
                "package com.example;",
                "public class OrderService {",
                "    public int sum(int[] arr) { int s = 0; for (int i = 0; i < arr.length; i++) { s += arr[i]; } return s; }",
                "    public String upper(String name) { return name == null ? \"\" : name.toUpperCase(); }",
                "}"
        ));
        Path order = src.resolve("com/example/Order.java");
        Files.write(order, List.of(
                "package com.example;",
                "public class Order { public long id; public String status; }"
        ));

        SootCfgBuilderUtil.CompileResult cr = SootCfgBuilderUtil.compileSources(List.of(svc, order));
        try {
            assertThat(cr.isSuccess()).as("JDK 标准库工程应编译成功: " + cr.getErrors()).isTrue();
            assertThat(cr.getOutputDir()).isNotNull();
            assertThat(Files.exists(cr.getOutputDir().resolve("com/example/OrderService.class"))).isTrue();
            assertThat(Files.exists(cr.getOutputDir().resolve("com/example/Order.class"))).isTrue();
        } finally {
            SootCfgBuilderUtil.deleteRecursively(cr.getTempRoot());
        }
    }

    @Test
    @DisplayName("含第三方import的类编译失败返回success=false")
    void compileWithThirdPartyImportFails() throws Exception {
        SootCfgBuilderUtil.configure(props());
        Path src = tempDir.resolve("third");
        Files.createDirectories(src);
        Path f = src.resolve("UsesLib.java");
        Files.write(f, List.of(
                "import com.nonexistent.library.ExternalLib;",
                "public class UsesLib { public String trim(String s) { return ExternalLib.trim(s); } }"
        ));

        SootCfgBuilderUtil.CompileResult cr = SootCfgBuilderUtil.compileSources(List.of(f));
        try {
            assertThat(cr.isSuccess()).as("第三方 import 应编译失败").isFalse();
            assertThat(cr.getErrors()).isNotEmpty();
        } finally {
            SootCfgBuilderUtil.deleteRecursively(cr.getTempRoot());
        }
    }

    @Test
    @DisplayName("try-catch 方法生成含 catch 异常边的字节码 CFG")
    void buildsMethodCfgWithCatchEdge() throws Exception {
        SootCfgBuilderUtil.configure(props());
        Path src = tempDir.resolve("demo");
        Files.createDirectories(src.resolve("com/example"));
        Path f = src.resolve("com/example/Calculator.java");
        Files.write(f, List.of(
                "package com.example;",
                "public class Calculator {",
                "    public int divide(int a, int b) {",
                "        int r = 0;",
                "        try { r = a / b; } catch (ArithmeticException e) { r = -1; }",
                "        return r;",
                "    }",
                "}"
        ));

        SootCfgBuilderUtil.CompileResult cr = SootCfgBuilderUtil.compileSources(List.of(f));
        try {
            assertThat(cr.isSuccess()).as("源码应编译成功: " + cr.getErrors()).isTrue();
            SootCfgBuilderUtil.CfgResult cfg = SootCfgBuilderUtil.buildMethodCfg(
                    cr, "com.example.Calculator", "divide", 2);
            assertThat(cfg.isSuccess()).as("Soot 单方法 CFG 应构建成功: " + cfg.getMessage()).isTrue();
            assertThat(cfg.getCfgJson()).startsWith("{").endsWith("}");
            assertThat(cfg.getCfgJson()).contains("\"nodes\":[").contains("\"edges\":[");
            assertThat(cfg.getCfgJson()).contains("\"type\":\"start\"").contains("\"type\":\"end\"");
            assertThat(cfg.getCfgJson()).contains("\"label\":\"catch\"");
        } finally {
            SootCfgBuilderUtil.deleteRecursively(cr.getTempRoot());
        }
    }

    @Test
    @DisplayName("循环+分支方法生成 if 分支节点")
    void buildsLoopMethodCfg() throws Exception {
        SootCfgBuilderUtil.configure(props());
        Path src = tempDir.resolve("demo2");
        Files.createDirectories(src.resolve("com/example"));
        Path f = src.resolve("com/example/Summer.java");
        Files.write(f, List.of(
                "package com.example;",
                "public class Summer {",
                "    public int sum(int[] arr) {",
                "        int s = 0;",
                "        for (int i = 0; i < arr.length; i++) { if (arr[i] > 0) { s += arr[i]; } }",
                "        return s;",
                "    }",
                "}"
        ));

        SootCfgBuilderUtil.CompileResult cr = SootCfgBuilderUtil.compileSources(List.of(f));
        try {
            assertThat(cr.isSuccess()).isTrue();
            SootCfgBuilderUtil.CfgResult cfg = SootCfgBuilderUtil.buildMethodCfg(
                    cr, "com.example.Summer", "sum", 1);
            assertThat(cfg.isSuccess()).as("CFG 构建成功: " + cfg.getMessage()).isTrue();
            assertThat(cfg.getCfgJson()).contains("\"type\":\"if\"");
        } finally {
            SootCfgBuilderUtil.deleteRecursively(cr.getTempRoot());
        }
    }

    // ===== GAP-048：Java 8/11/17 分版本适配 =====

    /** 读取 class 文件 major version（offset 6-7 字节），用于验证 --release 实际生效 */
    private int majorVersion(Path classFile) throws Exception {
        byte[] b = Files.readAllBytes(classFile);
        return ((b[6] & 0xFF) << 8) | (b[7] & 0xFF);
    }

    @Test
    @DisplayName("GAP-048: 探测 pom.xml maven.compiler.release=11 并按 Java 11 编译(major 55)")
    void detectsAndCompilesJava11FromPom() throws Exception {
        SootCfgBuilderUtil.configure(props());
        Path root = tempDir.resolve("j11proj");
        Files.createDirectories(root.resolve("src/main/java/com/example"));
        Path pom = root.resolve("pom.xml");
        Files.write(pom, List.of(
                "<project>",
                "  <properties>",
                "    <maven.compiler.release>11</maven.compiler.release>",
                "  </properties>",
                "</project>"
        ));
        Path f = root.resolve("src/main/java/com/example/Svc.java");
        Files.write(f, List.of(
                "package com.example;",
                "public class Svc { public String hi() { return \"hi\"; } }"
        ));

        SootCfgBuilderUtil.CompileResult cr = SootCfgBuilderUtil.compileSources(List.of(f), root);
        try {
            assertThat(cr.isSuccess()).as("Java 11 项目应编译成功: " + cr.getErrors()).isTrue();
            Path cls = cr.getOutputDir().resolve("com/example/Svc.class");
            assertThat(Files.exists(cls)).isTrue();
            assertThat(majorVersion(cls)).as("应产出 Java 11 字节码(major 55)").isEqualTo(55);
        } finally {
            SootCfgBuilderUtil.deleteRecursively(cr.getTempRoot());
        }
    }

    @Test
    @DisplayName("GAP-048: 探测 pom.xml maven.compiler.source=1.8 并按 Java 8 编译(major 52)")
    void detectsAndCompilesJava8FromPom() throws Exception {
        SootCfgBuilderUtil.configure(props());
        Path root = tempDir.resolve("j8proj");
        Files.createDirectories(root.resolve("src/main/java/com/example"));
        Path pom = root.resolve("pom.xml");
        Files.write(pom, List.of(
                "<project>",
                "  <properties>",
                "    <maven.compiler.source>1.8</maven.compiler.source>",
                "  </properties>",
                "</project>"
        ));
        Path f = root.resolve("src/main/java/com/example/Svc.java");
        Files.write(f, List.of(
                "package com.example;",
                "public class Svc { public int add(int a, int b) { return a + b; } }"
        ));

        SootCfgBuilderUtil.CompileResult cr = SootCfgBuilderUtil.compileSources(List.of(f), root);
        try {
            assertThat(cr.isSuccess()).as("Java 8 项目应编译成功: " + cr.getErrors()).isTrue();
            Path cls = cr.getOutputDir().resolve("com/example/Svc.class");
            assertThat(Files.exists(cls)).isTrue();
            assertThat(majorVersion(cls)).as("应产出 Java 8 字节码(major 52)").isEqualTo(52);
        } finally {
            SootCfgBuilderUtil.deleteRecursively(cr.getTempRoot());
        }
    }

    @Test
    @DisplayName("GAP-048: 探测 build.gradle sourceCompatibility=17 并按 Java 17 编译(major 61)")
    void detectsAndCompilesJava17FromGradle() throws Exception {
        SootCfgBuilderUtil.configure(props());
        Path root = tempDir.resolve("j17proj");
        Files.createDirectories(root.resolve("src/main/java/com/example"));
        Path gradle = root.resolve("build.gradle");
        Files.write(gradle, List.of(
                "plugins { id 'java' }",
                "sourceCompatibility = 17"
        ));
        Path f = root.resolve("src/main/java/com/example/Svc.java");
        Files.write(f, List.of(
                "package com.example;",
                "public class Svc { public String v() { return \"17\"; } }"
        ));

        SootCfgBuilderUtil.CompileResult cr = SootCfgBuilderUtil.compileSources(List.of(f), root);
        try {
            assertThat(cr.isSuccess()).as("Java 17 项目应编译成功: " + cr.getErrors()).isTrue();
            Path cls = cr.getOutputDir().resolve("com/example/Svc.class");
            assertThat(Files.exists(cls)).isTrue();
            assertThat(majorVersion(cls)).as("应产出 Java 17 字节码(major 61)").isEqualTo(61);
        } finally {
            SootCfgBuilderUtil.deleteRecursively(cr.getTempRoot());
        }
    }

    @Test
    @DisplayName("GAP-048: 无 pom/gradle 时默认按 Java 17 编译(major 61)，不破坏既有行为")
    void defaultsToJava17WhenNoBuildFile() throws Exception {
        SootCfgBuilderUtil.configure(props());
        Path src = tempDir.resolve("nodefault");
        Files.createDirectories(src.resolve("com/example"));
        Path f = src.resolve("com/example/Svc.java");
        Files.write(f, List.of(
                "package com.example;",
                "public class Svc { public int sq(int x) { return x * x; } }"
        ));

        SootCfgBuilderUtil.CompileResult cr = SootCfgBuilderUtil.compileSources(List.of(f));
        try {
            assertThat(cr.isSuccess()).as("默认(Java 17)应编译成功: " + cr.getErrors()).isTrue();
            Path cls = cr.getOutputDir().resolve("com/example/Svc.class");
            assertThat(majorVersion(cls)).isEqualTo(61);
        } finally {
            SootCfgBuilderUtil.deleteRecursively(cr.getTempRoot());
        }
    }
}
