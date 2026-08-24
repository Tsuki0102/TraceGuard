package com.traceguard.spi;

import com.traceguard.entity.Requirement;
import com.traceguard.util.RequirementAnalyzerUtil;
import org.springframework.stereotype.Component;

/**
 * AUD-10：Alloy 规约生成器适配（默认实现，specLanguage=alloy）。
 * 包装 {@link RequirementAnalyzerUtil#generateAlloySpec(Requirement)}，行为与原链路一致。
 */
@Component
public class AlloySpecGeneratorAdapter implements SpecGenerator {

    private final RequirementAnalyzerUtil requirementAnalyzerUtil;

    public AlloySpecGeneratorAdapter(RequirementAnalyzerUtil requirementAnalyzerUtil) {
        this.requirementAnalyzerUtil = requirementAnalyzerUtil;
    }

    @Override
    public String specLanguage() {
        return "alloy";
    }

    @Override
    public String generate(Requirement req) {
        return requirementAnalyzerUtil.generateAlloySpec(req);
    }
}
