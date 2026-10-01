package com.traceguard.spi;

import java.util.Collections;
import java.util.List;

/**
 * T11 多语言扩展：语言无关的代码编译结果（原 SootCfgBuilderUtil.CompileResult 的中性外壳）。
 * Java 链路将 Soot 编译结果经 {@link #of(Object, boolean, List)} 装入 nativeResult，
 * 各语言解析器实现只依赖 isSuccess()，需要原生上下文时自行解包 nativeResult。
 */
public final class CompileResult {

    /** 编译是否成功（决定解析器是否启用字节码级 CFG） */
    private final boolean success;
    /** 编译诊断信息（失败原因摘要） */
    private final List<String> errors;
    /** 实现私有的编译上下文（如 Java 的 SootCfgBuilderUtil.CompileResult），供具体解析器解包使用 */
    private final Object nativeResult;

    private CompileResult(boolean success, List<String> errors, Object nativeResult) {
        this.success = success;
        this.errors = errors == null ? Collections.emptyList() : errors;
        this.nativeResult = nativeResult;
    }

    public static CompileResult of(Object nativeResult, boolean success, List<String> errors) {
        return new CompileResult(success, errors, nativeResult);
    }

    /** 无编译上下文（语言本身无需编译，如 Python/解释型语言；解析器走 AST 级 CFG） */
    public static CompileResult none() {
        return new CompileResult(false, Collections.emptyList(), null);
    }

    public boolean isSuccess() {
        return success;
    }

    public List<String> getErrors() {
        return errors;
    }

    @SuppressWarnings("unchecked")
    public <T> T nativeResult() {
        return (T) nativeResult;
    }
}
