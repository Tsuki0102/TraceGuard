package com.traceguard.spi;

import com.traceguard.entity.CodeDefect;
import com.traceguard.entity.CodeUnit;
import com.traceguard.util.SemanticVectorUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.File;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T11 多语言扩展：CFamilyCodeParser（C/C++）单元测试。
 * 直接解析 samples/cpp-loglib 演示工程（无 DB / Spring 依赖），验证：
 * 函数名 declarator 穿透、类方法归属、CFG JSON 结构、语义特征词、圈复杂度、C/C++ 缺陷信号。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CFamilyCodeParserTest {

    private static final String SAMPLE_ROOT =
            Paths.get("..", "samples", "cpp-loglib").toAbsolutePath().normalize().toString();

    private CppCodeParser cppParser;
    private CCodeParser cParser;

    @BeforeAll
    void setUp() {
        cppParser = new CppCodeParser();
        cParser = new CCodeParser();
    }

    private List<CodeUnit> parse(String fileName) {
        File file = new File(SAMPLE_ROOT, "code/" + fileName);
        assertTrue(file.exists(), "样例文件不存在: " + file.getAbsolutePath());
        List<CodeUnit> units = cppParser.parseFileForAnalysis(file, SAMPLE_ROOT + "/code", CompileResult.none());
        assertFalse(units.isEmpty(), fileName + " 应至少解析出一个函数单元");
        return units;
    }

    @Test
    void languageAndExtensions() {
        assertEquals("cpp", cppParser.language());
        assertEquals("c", cParser.language());
        assertTrue(cppParser.sourceExtensions().contains(".cpp"));
        assertTrue(cppParser.sourceExtensions().contains(".c"), "cpp 解析器应兼容收集 .c 文件");
        assertEquals(List.of(".c", ".h"), cParser.sourceExtensions());
        // cpp 工程收集：logger.cpp + util.c（logger.h 无函数但文件会被收集）
        List<File> files = cppParser.collectSourceFiles(new File(SAMPLE_ROOT, "code"));
        assertEquals(3, files.size(), "应收集到 3 个源文件（.h/.cpp/.c）");
    }

    @Test
    void parseLoggerCppUnits() {
        List<CodeUnit> units = parse("logger.cpp");
        List<String> names = units.stream().map(CodeUnit::getMethodName).collect(Collectors.toList());
        // 函数名穿透 declarator + 类方法归属
        assertTrue(names.contains("open_file"), "应提取 open_file，实际: " + names);
        assertTrue(names.contains("write_line"));
        assertTrue(names.contains("maybe_flush"));
        assertTrue(names.contains("flush"));
        assertTrue(names.contains("format_timestamp"), "C 风格自由函数应被提取");
        CodeUnit write = units.stream()
                .filter(u -> "write_line".equals(u.getMethodName())).findFirst().orElseThrow();
        assertEquals("Logger", write.getClassName(), "类方法应归属 Logger 类");
        CodeUnit fmt = units.stream()
                .filter(u -> "format_timestamp".equals(u.getMethodName())).findFirst().orElseThrow();
        assertEquals("", fmt.getClassName(), "自由函数 className 应为空");
        for (CodeUnit unit : units) {
            assertNotNull(unit.getCfgData());
            assertTrue(unit.getCfgData().contains("\"nodes\"") && unit.getCfgData().contains("\"edges\""));
            assertNotNull(SemanticVectorUtil.parse(unit.getSemanticVector()).getTerms());
            assertTrue(unit.getEndLine() >= unit.getStartLine());
            assertNotNull(unit.getLogicDescription());
        }
        // doxygen 风格前注释应成为逻辑描述
        CodeUnit open = units.stream()
                .filter(u -> "open_file".equals(u.getMethodName())).findFirst().orElseThrow();
        assertTrue(open.getLogicDescription().contains("日志") || open.getLogicDescription().contains("句柄"),
                "doxygen 注释应成为逻辑描述: " + open.getLogicDescription());
    }

    @Test
    void parseUtilCByCppAndCParser() {
        // cpp 语法包兼容解析纯 C 文件
        List<CodeUnit> units = parse("util.c");
        List<String> names = units.stream().map(CodeUnit::getMethodName).collect(Collectors.toList());
        assertTrue(names.contains("init_prefix"));
        assertTrue(names.contains("valid_level"));
        assertTrue(names.contains("clamp_content"));
        // c 解析器同样可解析该文件
        List<CodeUnit> unitsByC = cParser.parseFileForAnalysis(
                new File(SAMPLE_ROOT, "code/util.c"), SAMPLE_ROOT + "/code", CompileResult.none());
        assertEquals(units.size(), unitsByC.size(), "C 与 C++ 解析器对纯 C 文件应产出一致单元数");
    }

    @Test
    void detectLoggerCppDefects() {
        File file = new File(SAMPLE_ROOT, "code/logger.cpp");
        List<CodeDefect> defects = cppParser.detectBasicDefects(file, SAMPLE_ROOT + "/code", 1L, 1L);
        List<String> types = defects.stream().map(CodeDefect::getDefectType).collect(Collectors.toList());
        assertTrue(types.contains("资源未释放"), "应检出 fopen 句柄泄漏，实际: " + types);
        assertTrue(types.contains("空指针风险"), "应检出 malloc 未判空");
        assertTrue(types.contains("异常吞没"), "应检出裸 catch(...)");
        assertFalse(types.contains("内存泄漏"), "logger.cpp 各函数均有 free，不应误报内存泄漏");
        CodeDefect leak = defects.stream()
                .filter(d -> "资源未释放".equals(d.getDefectType())).findFirst().orElseThrow();
        assertEquals("open_file", leak.getMethodName());
    }

    @Test
    void detectUtilCDefects() {
        File file = new File(SAMPLE_ROOT, "code/util.c");
        List<CodeDefect> defects = cppParser.detectBasicDefects(file, SAMPLE_ROOT + "/code", 1L, 1L);
        List<String> types = defects.stream().map(CodeDefect::getDefectType).collect(Collectors.toList());
        assertTrue(types.contains("内存泄漏"), "应检出 malloc 未释放，实际: " + types);
        assertTrue(types.contains("危险函数调用"), "应检出 strcpy 危险函数");
        CodeDefect leak = defects.stream()
                .filter(d -> "内存泄漏".equals(d.getDefectType())).findFirst().orElseThrow();
        assertEquals("init_prefix", leak.getMethodName());
        assertEquals("high", leak.getSeverity());
    }

    @Test
    void parseProjectIsolated() {
        ProjectParseResult result = cppParser.parseProject(SAMPLE_ROOT + "/code", CompileResult.none());
        // logger.cpp 8 函数 + util.c 3 函数 = 11（logger.h 仅声明，0 函数）
        assertTrue(result.codeUnits.size() >= 11, "应解析出 >= 11 个函数单元，实际: " + result.codeUnits.size());
        assertTrue(result.failures.isEmpty(), "样例工程不应有解析失败");
    }
}
