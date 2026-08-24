package com.traceguard.spi;

import com.traceguard.util.JavaCodeParserUtil;
import com.traceguard.util.SootCfgBuilderUtil;

/**
 * AUD-10 可扩展代码解析器接口（SPI）。
 * 预留多语言（C#、Python 等）代码解析扩展点：实现本接口并注册为 Spring Bean 即可由
 * {@link ParserRegistry} 按 language 解析，AnalysisService 通过配置 {@code traceguard.analysis.code-language}
 * 选择实现（默认 {@code java}）。保持最小接口面，避免与具体解析实现耦合。
 */
public interface CodeParser {

    /** 支持的语言标识（小写，如 {@code java}/{@code csharp}/{@code python}） */
    String language();

    /**
     * 解析代码工程为代码单元列表（带单文件隔离失败收集）。
     * @param projectPath 解压后的代码工程根路径
     * @param compileResult Soot 编译结果（无则传 null，由实现决定降级策略）
     */
    JavaCodeParserUtil.ProjectParseResult parseProject(String projectPath, SootCfgBuilderUtil.CompileResult compileResult);
}
