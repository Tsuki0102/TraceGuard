package com.traceguard.service.impl;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.traceguard.config.LlmProperties;
import com.traceguard.service.EmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地 BGE 向量化客户端（AUD-05，GAP-004，provider=local）
 * 基于 ONNX Runtime 离线推理 bge 系列嵌入模型，零外部网络依赖；中文语义向量化默认可用。
 * <p>
 * 模型加载：从 {@code traceguard.llm.embedding.local.model-path} 读取 ONNX 模型文件（如 bge-small-zh-v1.5 的
 * model.onnx），并从同目录加载 {@code vocab.txt}（WordPiece 词表）完成分词。模型文件或词表缺失时
 * {@link #available()} 返回 false，主流程自动回退规则模式（terms-only），不阻断分析任务。
 * </p>
 * <p>
 * 推理流程：WordPiece 分词 -> [CLS]...[SEP] -> ONNX 推理（last_hidden_state）-> 取 [CLS] 向量 -> L2 归一化。
 * 分批与失败处理由上层 {@code AnalysisService.embedSemantics} 控制；单条失败返回 null。
 * <p>注意：本类不再使用 @Service 自动装配，统一由 EmbeddingConfig 按 provider=local 创建单例 Bean。</p>
 */
public class LocalBgeEmbeddingClient implements EmbeddingService {

    private static final Logger LOGGER = LoggerFactory.getLogger(LocalBgeEmbeddingClient.class);

    private static final int MAX_SEQ_LEN = 512;
    private static final String CLS_TOKEN = "[CLS]";
    private static final String SEP_TOKEN = "[SEP]";
    private static final String UNK_TOKEN = "[UNK]";
    private static final String PAD_TOKEN = "[PAD]";

    private final String modelPath;
    private final int dim;
    private final boolean normalize;

    private OrtEnvironment ortEnv;
    private OrtSession ortSession;
    private Map<String, Integer> vocab;
    private volatile boolean loaded = false;

    public LocalBgeEmbeddingClient(LlmProperties properties) {
        LlmProperties.EmbeddingConfig ec = properties.getEmbedding();
        this.modelPath = (ec != null && ec.getModelPath() != null) ? ec.getModelPath() : "";
        this.dim = (ec != null) ? ec.getModelDim() : 512;
        this.normalize = (ec != null) && ec.isNormalize();
        tryInit();
    }

    /** 构造时尝试加载模型；失败仅记录日志，available()=false（安全回退） */
    private void tryInit() {
        if (modelPath == null || modelPath.trim().isEmpty()) {
            LOGGER.debug("LocalBgeEmbeddingClient：未配置 model-path，本地向量化不可用（回退规则模式）");
            return;
        }
        File modelFile = new File(modelPath);
        if (!modelFile.exists()) {
            LOGGER.warn("LocalBgeEmbeddingClient：模型文件不存在 {}，本地向量化不可用", modelPath);
            return;
        }
        File vocabFile = new File(modelFile.getParentFile(), "vocab.txt");
        if (!vocabFile.exists()) {
            LOGGER.warn("LocalBgeEmbeddingClient：词表文件不存在 {}，本地向量化不可用", vocabFile.getAbsolutePath());
            return;
        }
        try {
            this.vocab = loadVocab(vocabFile);
            this.ortEnv = OrtEnvironment.getEnvironment();
            byte[] modelBytes = Files.readAllBytes(modelFile.toPath());
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            this.ortSession = ortEnv.createSession(modelBytes, opts);
            this.loaded = true;
            LOGGER.info("LocalBgeEmbeddingClient：已加载本地 BGE 模型 {}（dim={}, vocab={}）",
                    modelPath, dim, vocab.size());
        } catch (Exception e) {
            LOGGER.warn("LocalBgeEmbeddingClient：模型加载失败，回退规则模式：{}", e.getMessage());
            this.loaded = false;
        }
    }

    @Override
    public boolean available() {
        return loaded && ortSession != null && vocab != null;
    }

    @Override
    public float[] embed(String text) {
        if (!available() || text == null || text.isEmpty()) {
            return null;
        }
        try {
            List<String> tokens = tokenize(text);
            long[] inputIds = new long[MAX_SEQ_LEN];
            long[] attentionMask = new long[MAX_SEQ_LEN];
            long[] tokenTypeIds = new long[MAX_SEQ_LEN];
            Arrays.fill(inputIds, vocab.getOrDefault(PAD_TOKEN, 0));
            int len = Math.min(tokens.size(), MAX_SEQ_LEN);
            for (int i = 0; i < len; i++) {
                inputIds[i] = vocab.getOrDefault(tokens.get(i), vocab.getOrDefault(UNK_TOKEN, 100));
                attentionMask[i] = 1L;
            }
            float[] vector = infer(inputIds, attentionMask, tokenTypeIds);
            if (vector != null && normalize) {
                l2Normalize(vector);
            }
            return vector;
        } catch (Exception e) {
            LOGGER.warn("LocalBgeEmbeddingClient：单条向量化失败：{}", e.getMessage());
            return null;
        }
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return new ArrayList<>();
        }
        List<float[]> result = new ArrayList<>(texts.size());
        for (String t : texts) {
            result.add(embed(t));
        }
        return result;
    }

    private float[] infer(long[] inputIds, long[] attentionMask, long[] tokenTypeIds) throws Exception {
        long[] shape = {1L, (long) MAX_SEQ_LEN};
        Map<String, OnnxTensor> inputs = new HashMap<>();
        inputs.put("input_ids", OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(inputIds), shape));
        inputs.put("attention_mask", OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(attentionMask), shape));
        inputs.put("token_type_ids", OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(tokenTypeIds), shape));
        try (OrtSession.Result outputs = ortSession.run(inputs)) {
            // 取 last_hidden_state；不同导出命名可能为 last_hidden_state / token_embeddings
            OnnxTensor out = (OnnxTensor) outputs.get(0);
            float[][][] hidden = (float[][][]) out.getValue();
            // 取 [CLS] 位置（index 0）作为句向量（BGE 官方推荐做法）
            float[] cls = new float[dim];
            if (hidden[0][0].length >= dim) {
                System.arraycopy(hidden[0][0], 0, cls, 0, dim);
            } else {
                System.arraycopy(hidden[0][0], 0, cls, 0, hidden[0][0].length);
            }
            return cls;
        }
    }

    /** WordPiece 分词（BGE 基于 BERT WordPiece 词表）：按空白粗切 -> subword 切分 */
    private List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        tokens.add(CLS_TOKEN);
        for (String piece : text.split("\\s+")) {
            if (piece.isEmpty()) continue;
            for (String word : splitByPunct(piece)) {
                if (word.isEmpty()) continue;
                addWordPieces(tokens, word);
            }
        }
        tokens.add(SEP_TOKEN);
        return tokens;
    }

    private String[] splitByPunct(String word) {
        // 简单按中文/英文标点边界切分，保证未登录符号也能被 WordPiece 处理
        return word.split("(?<=[\u4e00-\u9fa5a-zA-Z0-9])|(?=[\u4e00-\u9fa5a-zA-Z0-9])|"
                + "(?<=[^\\u4e00-\u9fa5a-zA-Z0-9])|(?=[^\\u4e00-\u9fa5a-zA-Z0-9])");
    }

    private void addWordPieces(List<String> tokens, String word) {
        // 英文小写化（BGE 词表多为小写）
        String w = word.toLowerCase();
        int start = 0;
        while (start < w.length()) {
            int end = w.length();
            boolean found = false;
            while (start < end) {
                String sub = w.substring(start, end);
                if (start > 0) sub = "##" + sub;
                if (vocab.containsKey(sub)) {
                    tokens.add(sub);
                    found = true;
                    start = end;
                    break;
                }
                end--;
            }
            if (!found) {
                tokens.add(UNK_TOKEN);
                start++;
            }
        }
    }

    private static void l2Normalize(float[] v) {
        double sum = 0;
        for (float x : v) sum += (double) x * x;
        double norm = Math.sqrt(sum);
        if (norm > 1e-12) {
            float inv = (float) (1.0 / norm);
            for (int i = 0; i < v.length; i++) v[i] *= inv;
        }
    }

    private static Map<String, Integer> loadVocab(File vocabFile) throws IOException {
        Map<String, Integer> map = new LinkedHashMap<>();
        try (InputStream in = Files.newInputStream(vocabFile.toPath());
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            int idx = 0;
            while ((line = reader.readLine()) != null) {
                String token = line.trim();
                if (!token.isEmpty()) {
                    map.put(token, idx);
                }
                idx++;
            }
        }
        // 兜底补充特殊符号
        map.putIfAbsent(PAD_TOKEN, 0);
        map.putIfAbsent(CLS_TOKEN, map.size());
        map.putIfAbsent(SEP_TOKEN, map.size());
        map.putIfAbsent(UNK_TOKEN, map.size());
        return map;
    }
}
