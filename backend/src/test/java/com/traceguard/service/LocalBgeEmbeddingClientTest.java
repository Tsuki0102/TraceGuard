package com.traceguard.service;

import com.traceguard.config.LlmProperties;
import com.traceguard.service.impl.LocalBgeEmbeddingClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AUD-05 / FUN-06：本地 BGE 嵌入模型加载与向量化验证。
 *
 * 前置：models/bge-small-zh-v1.5/onnx/model.onnx + vocab.txt 已下载
 * （scripts/download_bge_model.sh；当前脚本默认仓库需改为 Xenova/bge-small-zh-v1.5）。
 * 模型缺失时跳过（不视为失败），避免 CI 无模型阻塞。
 */
@DisplayName("本地 BGE 嵌入模型（AUD-05 / FUN-06）")
class LocalBgeEmbeddingClientTest {

    private String resolveModelPath() {
        String[] candidates = {
                "models/bge-small-zh-v1.5/onnx/model.onnx",
                "../models/bge-small-zh-v1.5/onnx/model.onnx",
                "../../models/bge-small-zh-v1.5/onnx/model.onnx",
        };
        for (String c : candidates) {
            if (new File(c).isFile()) {
                return new File(c).getAbsolutePath();
            }
        }
        return null;
    }

    @Test
    @DisplayName("模型存在时成功加载并输出 512 维归一化向量")
    void loadsLocalBge() {
        String path = resolveModelPath();
        if (path == null) {
            System.out.println("[LocalBge] 未找到本地 BGE 模型文件，跳过（需先执行下载脚本）");
            return;
        }
        LlmProperties props = new LlmProperties();
        props.getEmbedding().setProvider("local");
        props.getEmbedding().setModelPath(path);
        props.getEmbedding().setModelDim(512);
        LocalBgeEmbeddingClient client = new LocalBgeEmbeddingClient(props);

        assertThat(client.available())
                .as("模型与词表应成功加载（modelPath=%s）", path).isTrue();

        float[] v1 = client.embed("库存不足时禁止提交订单");
        float[] v2 = client.embed("库存不足不能下单");
        float[] v3 = client.embed("根据用户ID查询订单列表并返回分页结果");

        assertThat(v1).isNotNull().hasSize(512);
        assertThat(v2).isNotNull().hasSize(512);

        double sim12 = cosine(v1, v2);
        double sim13 = cosine(v1, v3);
        System.out.printf("[LocalBge] 语义相近=%.3f，语义无关=%.3f%n", sim12, sim13);
        // BGE 应能区分语义相近与无关文本（同领域正 > 无关负）
        assertThat(sim12).isGreaterThan(sim13);
        assertThat(sim12).isGreaterThan(0.4);
    }

    private double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return na > 0 && nb > 0 ? dot / (Math.sqrt(na) * Math.sqrt(nb)) : 0;
    }
}
