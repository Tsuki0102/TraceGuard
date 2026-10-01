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
 * T11 多语言扩展：PythonCodeParser 单元测试。
 * 直接解析 samples/python-inventory 演示工程（无 DB / Spring 依赖），验证：
 * 方法级 CodeUnit 提取、CFG JSON 结构、语义特征词、圈复杂度、基础缺陷信号。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PythonCodeParserTest {

    private PythonCodeParser parser;
    private String sampleRoot;

    @BeforeAll
    void setUp() {
        parser = new PythonCodeParser();
        sampleRoot = Paths.get("..", "samples", "python-inventory").toAbsolutePath().normalize().toString();
    }

    private List<CodeUnit> parse(String fileName) {
        File file = new File(sampleRoot, "code/" + fileName);
        assertTrue(file.exists(), "样例文件不存在: " + file.getAbsolutePath());
        List<CodeUnit> units = parser.parseFileForAnalysis(file, sampleRoot + "/code", CompileResult.none());
        assertFalse(units.isEmpty(), fileName + " 应至少解析出一个函数单元");
        return units;
    }

    @Test
    void languageAndExtensions() {
        assertEquals("python", parser.language());
        assertEquals(List.of(".py"), parser.sourceExtensions());
        List<File> files = parser.collectSourceFiles(new File(sampleRoot, "code"));
        assertEquals(2, files.size(), "应收集到 2 个 .py 文件");
    }

    @Test
    void parseStorePyUnits() {
        List<CodeUnit> units = parse("store.py");
        List<String> names = units.stream().map(CodeUnit::getMethodName).collect(Collectors.toList());
        assertTrue(names.contains("add_stock_record"), "应提取 add_stock_record，实际: " + names);
        assertTrue(names.contains("find_by_name"));
        assertTrue(names.contains("save_log"));
        assertTrue(names.contains("month_statistic"));
        for (CodeUnit unit : units) {
            // CFG / 语义向量 / 行号 / 哈希基础结构
            assertNotNull(unit.getCfgData(), unit.getMethodName() + " cfgData 不应为空");
            assertTrue(unit.getCfgData().contains("\"nodes\"") && unit.getCfgData().contains("\"edges\""),
                    "cfgData 应为 nodes/edges JSON");
            assertNotNull(unit.getSemanticVector());
            assertNotNull(SemanticVectorUtil.parse(unit.getSemanticVector()).getTerms());
            assertTrue(unit.getEndLine() >= unit.getStartLine());
            assertNotNull(unit.getContentHash());
            // docstring 逻辑描述
            assertNotNull(unit.getLogicDescription());
            assertFalse(unit.getLogicDescription().isBlank());
        }
    }

    @Test
    void parseServicePyWithDocstring() {
        List<CodeUnit> units = parse("service.py");
        CodeUnit outbound = units.stream()
                .filter(u -> "outbound".equals(u.getMethodName())).findFirst().orElse(null);
        assertNotNull(outbound, "应提取 outbound 函数");
        assertTrue(outbound.getLogicDescription().contains("出库") || outbound.getLogicDescription().contains("库存"),
                "docstring 应成为逻辑描述: " + outbound.getLogicDescription());
        // 出库含 if 分支 -> 语义特征词 branch
        assertTrue(outbound.getSemanticVector().contains("branch"),
                "outbound 语义特征词应含 branch: " + outbound.getSemanticVector());
        // 圈复杂度 >= 2（含 if）
        assertTrue(outbound.getCyclomaticComplexity() >= 2);
    }

    @Test
    void detectStorePyDefects() {
        File file = new File(sampleRoot, "code/store.py");
        List<CodeDefect> defects = parser.detectBasicDefects(file, sampleRoot + "/code", 1L, 1L);
        List<String> types = defects.stream().map(CodeDefect::getDefectType).collect(Collectors.toList());
        assertTrue(types.contains("可变默认参数"), "应检出可变默认参数，实际: " + types);
        assertTrue(types.contains("SQL注入"), "应检出 SQL f-string 注入");
        assertTrue(types.contains("资源未释放"), "应检出 open() 资源未释放");
        assertFalse(types.contains("逻辑死循环"), "store.py 不应误报死循环");
        // 行号与方法归属
        CodeDefect mutable = defects.stream()
                .filter(d -> "可变默认参数".equals(d.getDefectType())).findFirst().orElseThrow();
        assertEquals("add_stock_record", mutable.getMethodName());
        assertEquals("store.py", mutable.getFilePath());
        assertTrue(mutable.getLineNumber() > 0);
    }

    @Test
    void detectServicePyDefects() {
        File file = new File(sampleRoot, "code/service.py");
        List<CodeDefect> defects = parser.detectBasicDefects(file, sampleRoot + "/code", 1L, 1L);
        List<String> types = defects.stream().map(CodeDefect::getDefectType).collect(Collectors.toList());
        assertTrue(types.contains("空捕获异常"), "应检出裸 except，实际: " + types);
        assertTrue(types.contains("逻辑死循环"), "应检出 while True 死循环");
        CodeDefect loop = defects.stream()
                .filter(d -> "逻辑死循环".equals(d.getDefectType())).findFirst().orElseThrow();
        assertEquals("flush_cache_with_retry", loop.getMethodName(),
                "死循环应归属 flush_cache_with_retry（try 内 return 不算退出）");
    }

    @Test
    void parseProjectIsolated() {
        ProjectParseResult result = parser.parseProject(sampleRoot + "/code", CompileResult.none());
        assertTrue(result.codeUnits.size() >= 10, "store.py + service.py 应解析出 >= 10 个函数单元，实际: "
                + result.codeUnits.size());
        assertTrue(result.failures.isEmpty(), "样例工程不应有解析失败");
    }
}
