package com.traceguard.spi;

import com.traceguard.entity.CodeDefect;
import com.traceguard.entity.CodeUnit;
import com.traceguard.util.JavaCodeParserUtil;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * AUD-10 可扩展代码解析器接口（SPI），T11 多语言中性化改造。
 * 预留多语言（Java/Python/C# 等）代码解析扩展点：实现本接口并注册为 Spring Bean 即可由
 * {@link ParserRegistry} 按 language 解析。语言选择：项目 techStack 优先，回退全局配置
 * {@code traceguard.analysis.code-language}（默认 {@code java}）。
 *
 * <p>中性化要点：接口签名不再暴露 Java 专属类型（原 JavaCodeParserUtil.ProjectParseResult /
 * SootCfgBuilderUtil.CompileResult 已由 {@link ProjectParseResult} / {@link CompileResult} 替代）；
 * 编译上下文经 {@link CompileResult#nativeResult()} 由各实现自行解包（Java=Soot 编译结果，解释型语言传 none）。
 */
public interface CodeParser {

    /** 支持的语言标识（小写，如 {@code java}/{@code python}/{@code csharp}） */
    String language();

    /** 该语言的源文件扩展名（小写含点，如 {@code .java}/{@code .py}），供上传校验与文件收集使用 */
    List<String> sourceExtensions();

    /** 收集工程目录内全部源文件（由实现跳过 .git/__pycache__/target 等非源码目录） */
    List<File> collectSourceFiles(File projectDir);

    /**
     * 解析代码工程为代码单元列表（单文件隔离失败收集，不中断整体解析）。
     * @param projectPath 解压后的代码工程根路径
     * @param compileResult 编译上下文（解释型语言传 {@link CompileResult#none()}，由实现决定降级策略）
     */
    ProjectParseResult parseProject(String projectPath, CompileResult compileResult);

    /**
     * 增量解析：扫描工程内全部源文件（相对路径 -> 内容 sha256，与 code_unit.content_hash 同口径：UTF-8 全文）。
     * 默认不支持增量（返回空 Map，调用方将回退全量重解析）。
     */
    default Map<String, String> scanContentHashes(String projectPath) {
        return Collections.emptyMap();
    }

    /**
     * 增量解析：单文件重解析（仅解析变更/新增文件，供增量分析复用）。
     * 默认不支持（返回空列表）。
     */
    default List<CodeUnit> parseFileForAnalysis(File file, String projectPath, CompileResult compileResult) {
        return Collections.emptyList();
    }

    /**
     * 文件级基础缺陷检测（风险信号，与需求-代码一致性四类口径独立）。
     * 默认无检测结果。
     */
    default List<CodeDefect> detectBasicDefects(File file, String projectPath, Long projectId, Long taskId) {
        return Collections.emptyList();
    }

    /** 便捷重载：无编译上下文 */
    default ProjectParseResult parseProject(String projectPath) {
        return parseProject(projectPath, CompileResult.none());
    }

    /** 供 JavaCodeParserUtil 旧内部结构兼容的引用（保持原字段清单标记语义） */
    default boolean isFieldListUnit(CodeUnit unit) {
        return JavaCodeParserUtil.isFieldListUnit(unit);
    }
}
