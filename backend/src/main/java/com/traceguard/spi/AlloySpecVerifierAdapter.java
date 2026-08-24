package com.traceguard.spi;

import com.traceguard.util.AlloySpecVerifierUtil;
import org.springframework.stereotype.Component;

/**
 * AUD-10：Alloy 规约校验器适配（默认实现，specLanguage=alloy）。
 * 包装 {@link AlloySpecVerifierUtil#verify(String)}，行为与原链路一致（真实求解优先 + 结构校验降级）。
 */
@Component
public class AlloySpecVerifierAdapter implements SpecVerifier {

    @Override
    public String specLanguage() {
        return "alloy";
    }

    @Override
    public AlloySpecVerifierUtil.VerifyResult verify(String code) {
        return AlloySpecVerifierUtil.verify(code);
    }
}
