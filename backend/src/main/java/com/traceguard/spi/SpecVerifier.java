package com.traceguard.spi;

import com.traceguard.util.AlloySpecVerifierUtil;

/**
 * AUD-10 可扩展形式化规约校验器接口（SPI）。
 * 预留多形式化语言（Alloy、TLA+ 等）规约校验扩展点：实现本接口并注册为 Spring Bean 即可由
 * {@link SpecRegistry} 按 specLanguage 解析，AnalysisService 通过配置 {@code traceguard.analysis.spec-language}
 * 选择实现（默认 {@code alloy}）。
 */
public interface SpecVerifier {

    /** 支持的形式化语言标识（小写，如 {@code alloy}/{@code tlaplus}） */
    String specLanguage();

    /** 校验规约代码，返回结构化校验结果（语义求解或结构校验降级） */
    AlloySpecVerifierUtil.VerifyResult verify(String code);
}
