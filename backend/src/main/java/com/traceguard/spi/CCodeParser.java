package com.traceguard.spi;

import org.springframework.stereotype.Component;
import org.treesitter.TSLanguage;
import org.treesitter.TreeSitterC;

import java.util.List;

/**
 * T11 多语言扩展：C 语言解析器（language=c），tree-sitter-c 语法包。
 * 源文件集合：.c（源码）+ .h（头文件内联函数定义）。
 */
@Component
public class CCodeParser extends CFamilyCodeParser {

    @Override
    protected String languageId() {
        return "c";
    }

    @Override
    protected TSLanguage tsLanguage() {
        return new TreeSitterC();
    }

    @Override
    protected List<String> extensions() {
        return List.of(".c", ".h");
    }
}
