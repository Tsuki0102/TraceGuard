package com.traceguard.spi;

import com.traceguard.common.BusinessException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AUD-10：形式化规约生成/校验注册表。Spring 注入所有 {@link SpecGenerator}/{@link SpecVerifier} Bean，
 * 构建 specLanguage -> 实现 映射，供 AnalysisService 按配置 {@code traceguard.analysis.spec-language} 选取。
 */
@Component
public class SpecRegistry {

    private final Map<String, SpecGenerator> generators = new ConcurrentHashMap<>();
    private final Map<String, SpecVerifier> verifiers = new ConcurrentHashMap<>();

    public SpecRegistry(List<SpecGenerator> generators, List<SpecVerifier> verifiers) {
        if (generators != null) {
            for (SpecGenerator g : generators) {
                this.generators.put(g.specLanguage().toLowerCase(), g);
            }
        }
        if (verifiers != null) {
            for (SpecVerifier v : verifiers) {
                this.verifiers.put(v.specLanguage().toLowerCase(), v);
            }
        }
    }

    public SpecGenerator getSpecGenerator(String specLanguage) {
        SpecGenerator g = generators.get(specLanguage.toLowerCase());
        if (g == null) {
            throw new BusinessException("未找到形式化语言[" + specLanguage + "]的规约生成器，可用语言：" + generators.keySet());
        }
        return g;
    }

    public SpecVerifier getSpecVerifier(String specLanguage) {
        SpecVerifier v = verifiers.get(specLanguage.toLowerCase());
        if (v == null) {
            throw new BusinessException("未找到形式化语言[" + specLanguage + "]的规约校验器，可用语言：" + verifiers.keySet());
        }
        return v;
    }
}
