package com.traceguard.spi;

import com.traceguard.util.JavaCodeParserUtil;
import com.traceguard.util.SootCfgBuilderUtil;
import org.springframework.stereotype.Component;

/**
 * AUD-10：Java 代码解析器适配（默认实现，language=java）。
 * 包装 {@link JavaCodeParserUtil#parseProjectIsolated(String, SootCfgBuilderUtil.CompileResult)}，行为与原链路一致。
 */
@Component
public class JavaCodeParserAdapter implements CodeParser {

    private final JavaCodeParserUtil javaCodeParserUtil;

    public JavaCodeParserAdapter(JavaCodeParserUtil javaCodeParserUtil) {
        this.javaCodeParserUtil = javaCodeParserUtil;
    }

    @Override
    public String language() {
        return "java";
    }

    @Override
    public JavaCodeParserUtil.ProjectParseResult parseProject(String projectPath, SootCfgBuilderUtil.CompileResult compileResult) {
        return javaCodeParserUtil.parseProjectIsolated(projectPath, compileResult);
    }
}
