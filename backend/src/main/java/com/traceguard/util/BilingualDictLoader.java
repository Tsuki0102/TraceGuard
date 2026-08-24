package com.traceguard.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * GAP-024：中英语义词典加载工具
 * 从 classpath 或外部目录加载 zh-en-dict.json，支持本地覆盖扩展
 */
public final class BilingualDictLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger(BilingualDictLoader.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 默认词典路径（classpath） */
    private static final String DEFAULT_DICT_PATH = "zh-en-dict.json";

    /** 外部词典文件路径（可选，用于本地覆盖扩展） */
    private static final String EXTERNAL_DICT_PATH = "./config/zh-en-dict.json";

    private BilingualDictLoader() {
    }

    /**
     * 加载中英语义词典
     * 1. 先加载 classpath 默认词典
     * 2. 若外部文件存在，则合并（外部覆盖内部）
     *
     * @return 词典 Map<中文词，英文同义词列表>
     */
    public static Map<String, List<String>> loadDictionary() {
        Map<String, List<String>> dict = new LinkedHashMap<>();

        // 1. 加载 classpath 默认词典
        try (InputStream in = BilingualDictLoader.class.getClassLoader()
                .getResourceAsStream(DEFAULT_DICT_PATH)) {
            if (in != null) {
                Map<String, List<String>> internal = parseDict(in);
                dict.putAll(internal);
                LOGGER.info("加载默认中英语义词典：{} 个词条", internal.size());
            } else {
                LOGGER.warn("未找到默认词典文件：{}", DEFAULT_DICT_PATH);
            }
        } catch (IOException e) {
            LOGGER.error("加载默认词典失败：{}", e.getMessage());
        }

        // 2. 加载外部词典（覆盖扩展）
        File externalFile = new File(EXTERNAL_DICT_PATH);
        if (externalFile.exists() && externalFile.isFile()) {
            try (FileInputStream in = new FileInputStream(externalFile)) {
                Map<String, List<String>> external = parseDict(in);
                // 外部词典覆盖内部词典（支持本地扩展）
                for (Map.Entry<String, List<String>> entry : external.entrySet()) {
                    dict.put(entry.getKey(), entry.getValue());
                }
                LOGGER.info("加载外部中英语义词典：{} 个词条（已合并覆盖）", external.size());
            } catch (IOException e) {
                LOGGER.error("加载外部词典失败：{}", e.getMessage());
            }
        } else {
            LOGGER.debug("未找到外部词典文件：{}（可选配置）", EXTERNAL_DICT_PATH);
        }

        return dict;
    }

    /**
     * 解析词典 JSON
     */
    private static Map<String, List<String>> parseDict(InputStream in) throws IOException {
        Map<String, List<String>> dict = new LinkedHashMap<>();
        JsonNode root = MAPPER.readTree(in);

        // 兼容旧格式（直接是 entries）和新格式（带 version/entries 包装）
        JsonNode entriesNode;
        if (root.has("entries")) {
            entriesNode = root.get("entries");
        } else {
            entriesNode = root;
        }

        if (entriesNode.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = entriesNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String zhWord = field.getKey();
                JsonNode enArray = field.getValue();
                if (enArray.isArray()) {
                    List<String> enWords = new ArrayList<>();
                    for (JsonNode item : enArray) {
                        if (item.isTextual()) {
                            enWords.add(item.asText());
                        }
                    }
                    dict.put(zhWord, enWords);
                }
            }
        }

        return dict;
    }

    /**
     * 保存词典到外部文件（用于管理工具动态更新）
     */
    public static void saveDictionary(Map<String, List<String>> dict, String filePath) throws IOException {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("version", "1.0");
        output.put("description", "中英语义词典：中文需求词 -> 代码中常见英文等价词");
        output.put("entries", dict);

        File file = new File(filePath);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        try (FileOutputStream out = new FileOutputStream(file)) {
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(out, output);
        }
        LOGGER.info("词典已保存到：{}", filePath);
    }
}