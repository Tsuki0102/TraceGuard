package com.traceguard.service;

import java.util.List;

/**
 * Embedding 向量化服务（GAP-004）
 * 需求与代码语义表征进入同一真实向量空间；跨语言匹配不再依赖硬编码词典。
 * 实现方负责分批、限流与超时；失败元素以 null 表示，不阻断主流程。
 */
public interface EmbeddingService {

    /** 单文本向量化；失败返回 null（不抛异常） */
    float[] embed(String text);

    /** 批量向量化（实现方负责分批与限流）；返回与输入等长的列表，失败元素为 null */
    List<float[]> embedBatch(List<String> texts);

    /** 当前实现是否可用（配置齐全且连通/模型可用） */
    boolean available();
}