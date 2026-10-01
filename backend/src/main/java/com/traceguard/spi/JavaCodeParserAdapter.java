package com.traceguard.spi;

import com.traceguard.entity.CodeDefect;
import com.traceguard.entity.CodeUnit;
import com.traceguard.util.JavaCodeParserUtil;
import com.traceguard.util.SootCfgBuilderUtil;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AUD-10：Java 代码解析器适配（默认实现，language=java）。
 * 包装 {@link JavaCodeParserUtil}，行为与原链路一致；T11 中性化改造后对外只暴露中性类型，
 * Soot 编译上下文经 {@link CompileResult#nativeResult()} 解包传入原实现。
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
    public List<String> sourceExtensions() {
        return List.of(".java");
    }

    @Override
    public List<File> collectSourceFiles(File projectDir) {
        List<File> files = new ArrayList<>();
        collectJavaFiles(projectDir, files);
        return files;
    }

    @Override
    public ProjectParseResult parseProject(String projectPath, CompileResult compileResult) {
        SootCfgBuilderUtil.CompileResult soot = unwrap(compileResult);
        JavaCodeParserUtil.ProjectParseResult raw = javaCodeParserUtil.parseProjectIsolated(projectPath, soot);
        ProjectParseResult result = new ProjectParseResult();
        result.codeUnits.addAll(raw.codeUnits);
        result.failures.addAll(raw.failures);
        return result;
    }

    @Override
    public Map<String, String> scanContentHashes(String projectPath) {
        return javaCodeParserUtil.scanContentHashes(projectPath);
    }

    @Override
    public List<CodeUnit> parseFileForAnalysis(File file, String projectPath, CompileResult compileResult) {
        return javaCodeParserUtil.parseFileForAnalysis(file, projectPath, unwrap(compileResult));
    }

    @Override
    public List<CodeDefect> detectBasicDefects(File file, String projectPath, Long projectId, Long taskId) {
        return javaCodeParserUtil.detectBasicDefects(file, projectPath, projectId, taskId);
    }

    private SootCfgBuilderUtil.CompileResult unwrap(CompileResult compileResult) {
        if (compileResult == null) {
            return null;
        }
        SootCfgBuilderUtil.CompileResult soot = compileResult.nativeResult();
        return soot != null && soot.isSuccess() ? soot : null;
    }

    private void collectJavaFiles(File dir, List<File> files) {
        File[] list = dir.listFiles();
        if (list == null) return;
        for (File f : list) {
            if (f.isDirectory()) {
                if (!f.getName().startsWith(".") && !f.getName().equals("target") && !f.getName().equals("build")) {
                    collectJavaFiles(f, files);
                }
            } else if (f.getName().endsWith(".java")) {
                files.add(f);
            }
        }
    }
}
