package com.traceguard.config;

/**
 * FR-CODE-001 规则3/4（2.4 整改项）：代码解析范围运行期持有者。
 * 与 {@link RuleConfigHolder} 同模式：JavaCodeParserUtil 在解析时读取静态配置；
 * SystemConfigService 在启动与配置变更时调用 {@link #apply(CodeParseScope)} 刷新，热生效。
 */
public final class CodeParseScopeHolder {

    private static volatile CodeParseScope current = new CodeParseScope();

    private CodeParseScopeHolder() {
    }

    public static void apply(CodeParseScope scope) {
        current = (scope != null) ? scope : new CodeParseScope();
    }

    public static CodeParseScope get() {
        return current;
    }

    /** 重置为不过滤 */
    public static void reset() {
        current = new CodeParseScope();
    }
}
