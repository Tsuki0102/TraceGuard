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
 * T11 多语言扩展：GoCodeParser 单元测试。
 * 直接解析 samples/go-taskrunner 演示工程（无 DB / Spring 依赖），验证：
 * method_declaration 归属（receiver）、function_declaration 提取、CFG JSON 结构、
 * 语义特征词（goroutine/defer）、圈复杂度、Go 特色缺陷信号。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GoCodeParserTest {

    private static final String SAMPLE_ROOT =
            Paths.get("..", "samples", "go-taskrunner").toAbsolutePath().normalize().toString();

    private GoCodeParser parser;

    @BeforeAll
    void setUp() {
        parser = new GoCodeParser();
    }

    private List<CodeUnit> parse(String fileName) {
        File file = new File(SAMPLE_ROOT, "code/" + fileName);
        assertTrue(file.exists(), "样例文件不存在: " + file.getAbsolutePath());
        List<CodeUnit> units = parser.parseFileForAnalysis(file, SAMPLE_ROOT + "/code", CompileResult.none());
        assertFalse(units.isEmpty(), fileName + " 应至少解析出一个函数单元");
        return units;
    }

    @Test
    void languageAndExtensions() {
        assertEquals("go", parser.language());
        assertEquals(List.of(".go"), parser.sourceExtensions());
        List<File> files = parser.collectSourceFiles(new File(SAMPLE_ROOT, "code"));
        assertEquals(2, files.size(), "应收集到 2 个 .go 文件");
    }

    @Test
    void parseRunnerGoUnits() {
        List<CodeUnit> units = parse("runner.go");
        List<String> names = units.stream().map(CodeUnit::getMethodName).collect(Collectors.toList());
        assertTrue(names.contains("NewRunner"), "顶层函数应提取，实际: " + names);
        assertTrue(names.contains("RunTask"));
        assertTrue(names.contains("MaybeFlush"));
        assertTrue(names.contains("Cleanup"));
        // method_declaration receiver 归属：TaskRunner
        CodeUnit run = units.stream()
                .filter(u -> "RunTask".equals(u.getMethodName())).findFirst().orElseThrow();
        assertEquals("TaskRunner", run.getClassName(), "方法应归属 TaskRunner，实际: " + run.getClassName());
        CodeUnit newRunner = units.stream()
                .filter(u -> "NewRunner".equals(u.getMethodName())).findFirst().orElseThrow();
        assertEquals("", newRunner.getClassName(), "顶层函数 className 应为空");
        for (CodeUnit unit : units) {
            assertNotNull(unit.getCfgData());
            assertTrue(unit.getCfgData().contains("\"nodes\"") && unit.getCfgData().contains("\"edges\""));
            assertNotNull(SemanticVectorUtil.parse(unit.getSemanticVector()).getTerms());
            assertTrue(unit.getEndLine() >= unit.getStartLine());
            assertNotNull(unit.getLogicDescription());
        }
    }

    @Test
    void parseUtilGoUnits() {
        List<CodeUnit> units = parse("util.go");
        List<String> names = units.stream().map(CodeUnit::getMethodName).collect(Collectors.toList());
        assertTrue(names.contains("ParseSize"));
        assertTrue(names.contains("ValidLevel"));
        assertTrue(names.contains("LevelWeight"));
        // godoc 注释 -> 逻辑描述
        CodeUnit parse = units.stream()
                .filter(u -> "ParseSize".equals(u.getMethodName())).findFirst().orElseThrow();
        assertTrue(parse.getLogicDescription().contains("解析") || parse.getLogicDescription().contains("大小"),
                "godoc 注释应成为逻辑描述: " + parse.getLogicDescription());
        // panic 调用 -> 语义特征词包含 panic（snake 切分）
        assertTrue(parse.getSemanticVector().contains("panic") || parse.getSemanticVector().contains("fmt"),
                "语义特征词应含调用名: " + parse.getSemanticVector());
    }

    @Test
    void detectRunnerGoDefects() {
        File file = new File(SAMPLE_ROOT, "code/runner.go");
        List<CodeDefect> defects = parser.detectBasicDefects(file, SAMPLE_ROOT + "/code", 1L, 1L);
        List<String> types = defects.stream().map(CodeDefect::getDefectType).collect(Collectors.toList());
        assertTrue(types.contains("错误未处理"), "应检出 err 未检查，实际: " + types);
        assertTrue(types.contains("资源未释放"), "应检出 Open 无 Close");
        assertTrue(types.contains("锁未释放"), "应检出 Lock 无 Unlock");
        assertTrue(types.contains("错误被忽略"), "应检出 error 被 _ 丢弃");
        // 归属校验
        CodeDefect unhandled = defects.stream()
                .filter(d -> "错误未处理".equals(d.getDefectType())).findFirst().orElseThrow();
        assertEquals("RunTask", unhandled.getMethodName());
        assertEquals("high", unhandled.getSeverity());
        CodeDefect lock = defects.stream()
                .filter(d -> "锁未释放".equals(d.getDefectType())).findFirst().orElseThrow();
        assertEquals("UpdateCounter", lock.getMethodName());
    }

    @Test
    void detectUtilGoDefects() {
        File file = new File(SAMPLE_ROOT, "code/util.go");
        List<CodeDefect> defects = parser.detectBasicDefects(file, SAMPLE_ROOT + "/code", 1L, 1L);
        List<String> types = defects.stream().map(CodeDefect::getDefectType).collect(Collectors.toList());
        assertTrue(types.contains("panic 滥用"), "应检出 panic 滥用，实际: " + types);
        assertFalse(types.contains("错误未处理"), "util.go 无 err 接收，不应误报");
        CodeDefect panicDefect = defects.stream()
                .filter(d -> "panic 滥用".equals(d.getDefectType())).findFirst().orElseThrow();
        assertEquals("ParseSize", panicDefect.getMethodName());
    }

    @Test
    void parseProjectIsolated() {
        ProjectParseResult result = parser.parseProject(SAMPLE_ROOT + "/code", CompileResult.none());
        // runner.go 7 函数 + util.go 4 函数 = 11
        assertTrue(result.codeUnits.size() >= 11, "应解析出 >= 11 个函数单元，实际: " + result.codeUnits.size());
        assertTrue(result.failures.isEmpty(), "样例工程不应有解析失败");
    }
}
