package com.traceguard.spi;

import org.springframework.stereotype.Component;
import org.treesitter.TSLanguage;
import org.treesitter.TreeSitterCpp;

import java.util.List;

/**
 * T11 多语言扩展：C++ 语言解析器（language=cpp），tree-sitter-cpp 语法包（超集，可解析纯 C 代码）。
 * 源文件集合：.cpp/.cc/.cxx（源码）+ .hpp/.hh/.h（头文件）。
 */
@Component
public class CppCodeParser extends CFamilyCodeParser {

    @Override
    protected String languageId() {
        return "cpp";
    }

    @Override
    protected TSLanguage tsLanguage() {
        return new TreeSitterCpp();
    }

    @Override
    protected List<String> extensions() {
        return List.of(".cpp", ".cc", ".cxx", ".hpp", ".hh", ".h", ".c");
    }
}
