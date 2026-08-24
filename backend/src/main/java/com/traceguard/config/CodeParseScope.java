package com.traceguard.config;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * FR-CODE-001 规则3/4（2.4 整改项）：代码解析范围配置。
 * 支持按包/类/方法三级过滤（包含与排除），用于控制解析范围（如"仅解析业务包"）。
 * 规则：
 *  - include* 非空时，目标必须匹配任一 include；为空表示不限
 *  - exclude* 命中任一即排除（优先于 include）
 *  - 包/类支持通配：`com.example.*`（包前缀）、`*service*`（包含通配）
 *  - 类匹配支持简单类名与 包.类 全限定名两种形式
 */
public class CodeParseScope {

    /** 包含包（包名或前缀通配，如 com.example.*；空=不限） */
    private List<String> includePackages = new ArrayList<>();
    /** 排除包 */
    private List<String> excludePackages = new ArrayList<>();
    /** 包含类（简单类名或 包.类 全限定名；空=不限） */
    private List<String> includeClasses = new ArrayList<>();
    /** 排除类 */
    private List<String> excludeClasses = new ArrayList<>();
    /** 包含方法名（简单方法名；空=不限） */
    private List<String> includeMethods = new ArrayList<>();
    /** 排除方法名 */
    private List<String> excludeMethods = new ArrayList<>();

    public List<String> getIncludePackages() { return includePackages; }
    public void setIncludePackages(List<String> includePackages) { this.includePackages = includePackages != null ? includePackages : new ArrayList<>(); }
    public List<String> getExcludePackages() { return excludePackages; }
    public void setExcludePackages(List<String> excludePackages) { this.excludePackages = excludePackages != null ? excludePackages : new ArrayList<>(); }
    public List<String> getIncludeClasses() { return includeClasses; }
    public void setIncludeClasses(List<String> includeClasses) { this.includeClasses = includeClasses != null ? includeClasses : new ArrayList<>(); }
    public List<String> getExcludeClasses() { return excludeClasses; }
    public void setExcludeClasses(List<String> excludeClasses) { this.excludeClasses = excludeClasses != null ? excludeClasses : new ArrayList<>(); }
    public List<String> getIncludeMethods() { return includeMethods; }
    public void setIncludeMethods(List<String> includeMethods) { this.includeMethods = includeMethods != null ? includeMethods : new ArrayList<>(); }
    public List<String> getExcludeMethods() { return excludeMethods; }
    public void setExcludeMethods(List<String> excludeMethods) { this.excludeMethods = excludeMethods != null ? excludeMethods : new ArrayList<>(); }

    /** 全为空即不过滤 */
    public boolean isEmpty() {
        return includePackages.isEmpty() && excludePackages.isEmpty()
                && includeClasses.isEmpty() && excludeClasses.isEmpty()
                && includeMethods.isEmpty() && excludeMethods.isEmpty();
    }

    /** 类级匹配（包/类维度，不含方法过滤）：true=保留该类 */
    public boolean matchesClass(String packageName, String className) {
        String pkg = packageName == null ? "" : packageName.trim();
        String cls = className == null ? "" : className.trim();
        String fullClassName = pkg.isEmpty() ? cls : pkg + "." + cls;
        if (!includePackages.isEmpty() && !matchAny(includePackages, pkg)) {
            return false;
        }
        if (matchAny(excludePackages, pkg)) {
            return false;
        }
        if (!includeClasses.isEmpty()
                && !matchAny(includeClasses, cls) && !matchAny(includeClasses, fullClassName)) {
            return false;
        }
        return !matchAny(excludeClasses, cls) && !matchAny(excludeClasses, fullClassName);
    }

    /** 方法级匹配（类级 + 方法名过滤）：true=保留该方法 */
    public boolean matchesMethod(String packageName, String className, String methodName) {
        if (!matchesClass(packageName, className)) {
            return false;
        }
        String m = methodName == null ? "" : methodName.trim();
        if (!includeMethods.isEmpty() && !matchAny(includeMethods, m)) {
            return false;
        }
        return !matchAny(excludeMethods, m);
    }

    /** 模式匹配：支持前缀通配（com.example.*）与任意位置通配（*service*），普通模式精确相等 */
    private boolean matchAny(List<String> patterns, String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (String p : patterns) {
            if (p == null || p.trim().isEmpty()) {
                continue;
            }
            String pat = p.trim();
            if (pat.endsWith(".*")) {
                String prefix = pat.substring(0, pat.length() - 2);
                if (value.equals(prefix) || value.startsWith(prefix + ".")) {
                    return true;
                }
            } else if (pat.contains("*")) {
                String regex = Pattern.quote(pat)
                        .replace("*", "\\E.*\\Q")
                        .replace("\\Q\\E", "");
                if (Pattern.compile(regex).matcher(value).matches()) {
                    return true;
                }
            } else if (value.equals(pat)) {
                return true;
            }
        }
        return false;
    }
}
