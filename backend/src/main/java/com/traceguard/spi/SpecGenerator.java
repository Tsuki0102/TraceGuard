package com.traceguard.spi;

import com.traceguard.entity.Requirement;

/**
 * AUD-10 可扩展形式化规约生成器接口（SPI）。
 * 预留多形式化语言（Alloy、TLA+ 等）规约生成扩展点：实现本接口并注册为 Spring Bean 即可由
 * {@link SpecRegistry} 按 specLanguage 解析，AnalysisService 通过配置 {@code traceguard.analysis.spec-language}
 * 选择实现（默认 {@code alloy}）。
 */
public interface SpecGenerator {

    /** 支持的形式化语言标识（小写，如 {@code alloy}/{@code tlaplus}） */
    String specLanguage();

    /** 根据需求生成形式化规约代码（如 Alloy 源文本） */
    String generate(Requirement req);
}
