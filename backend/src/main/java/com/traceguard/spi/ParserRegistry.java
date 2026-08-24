package com.traceguard.spi;

import com.traceguard.common.BusinessException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AUD-10：代码解析器注册表。Spring 注入所有 {@link CodeParser} Bean，构建 language -> 实现 映射，
 * 供 AnalysisService 按配置 {@code traceguard.analysis.code-language} 选取。
 */
@Component
public class ParserRegistry {

    private final Map<String, CodeParser> registry = new ConcurrentHashMap<>();

    public ParserRegistry(List<CodeParser> parsers) {
        if (parsers != null) {
            for (CodeParser p : parsers) {
                registry.put(p.language().toLowerCase(), p);
            }
        }
    }

    public CodeParser getCodeParser(String language) {
        CodeParser p = registry.get(language.toLowerCase());
        if (p == null) {
            throw new BusinessException("未找到语言[" + language + "]的代码解析器，可用语言：" + registry.keySet());
        }
        return p;
    }
}
