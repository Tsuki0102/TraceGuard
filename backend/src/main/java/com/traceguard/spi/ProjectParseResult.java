package com.traceguard.spi;

import com.traceguard.entity.CodeUnit;
import com.traceguard.service.ParseFailure;

import java.util.ArrayList;
import java.util.List;

/**
 * T11 多语言扩展：语言无关的工程解析结果（原 JavaCodeParserUtil.ProjectParseResult 的中性化）。
 * codeUnits 为提取出的方法级代码单元；failures 为单文件隔离解析的失败清单（key 为源码相对路径）。
 */
public class ProjectParseResult {

    public final List<CodeUnit> codeUnits = new ArrayList<>();
    public final List<ParseFailure> failures = new ArrayList<>();
}
