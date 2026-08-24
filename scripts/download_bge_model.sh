#!/usr/bin/env bash
# AUD-05：下载本地 BGE 嵌入模型（ONNX），供 provider=local 离线向量化使用
# 用法：bash scripts/download_bge_model.sh [模型名] [输出目录]
# 默认下载 bge-small-zh-v1.5 的 ONNX 版本（约 130MB，dim=512）
# 注意：官方 BAAI/bge-small-zh-v1.5 仓库不含 onnx 产物，默认改用社区 ONNX 导出 Xenova/bge-small-zh-v1.5
#       （onnx/model.onnx 与 vocab.txt 同目录，后端 LocalBgeEmbeddingClient 按此加载）。
# 下载完成后自动把 EMBEDDING_PROVIDER/MODEL_PATH/MODEL_DIM 写入项目根目录 .env，开箱即用
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

MODEL_NAME="${1:-Xenova/bge-small-zh-v1.5}"
OUT_DIR="${2:-models/bge-small-zh-v1.5}"
# 下载到项目根下，便于脚本自动写入 .env
MODEL_ABS_DIR="$PROJECT_ROOT/$OUT_DIR"

mkdir -p "$MODEL_ABS_DIR/onnx"

echo ">> 下载 $MODEL_NAME (ONNX) 到 $OUT_DIR ..."
if command -v huggingface-cli >/dev/null 2>&1; then
  huggingface-cli download "$MODEL_NAME" --include "onnx/*" --local-dir "$MODEL_ABS_DIR"
elif command -v python >/dev/null 2>&1; then
  python - "$MODEL_NAME" "$MODEL_ABS_DIR" <<'PY'
import sys, os
try:
    from huggingface_hub import snapshot_download
    snapshot_download(sys.argv[1], local_dir=sys.argv[2], allow_patterns=["onnx/*", "vocab.txt", "tokenizer.json"])
except ImportError:
    print("缺少依赖：请先安装 huggingface_hub (pip install -U huggingface_hub)，或安装 huggingface-cli")
    sys.exit(1)
PY
else
  echo "未找到 huggingface 下载工具，请手动下载 $MODEL_NAME 的 onnx/ 与 vocab.txt 到 $MODEL_ABS_DIR" >&2
  exit 1
fi

# 适配配置项：默认模型文件名为 model.onnx（部分导出为 model_qint8.onnx 等，按实际重命名）
if [ ! -f "$MODEL_ABS_DIR/onnx/model.onnx" ]; then
  CANDIDATE=$(ls "$MODEL_ABS_DIR"/onnx/*.onnx 2>/dev/null | head -n1 || true)
  if [ -n "$CANDIDATE" ]; then
    cp "$CANDIDATE" "$MODEL_ABS_DIR/onnx/model.onnx"
    echo ">> 已将 $CANDIDATE 复制为 $MODEL_ABS_DIR/onnx/model.onnx"
  fi
fi

if [ ! -f "$MODEL_ABS_DIR/onnx/model.onnx" ]; then
  echo ">> 错误：未找到 onnx 模型文件，请检查下载产物。" >&2
  exit 1
fi

# 后端 LocalBgeEmbeddingClient 从模型同目录加载 vocab.txt（WordPiece 词表），
# 而 Xenova 仓库的 vocab.txt 位于仓库根，需复制到 onnx/ 下
if [ -f "$MODEL_ABS_DIR/vocab.txt" ] && [ ! -f "$MODEL_ABS_DIR/onnx/vocab.txt" ]; then
  cp "$MODEL_ABS_DIR/vocab.txt" "$MODEL_ABS_DIR/onnx/vocab.txt"
  echo ">> 已将 vocab.txt 复制到 $MODEL_ABS_DIR/onnx/vocab.txt（供后端同目录加载）"
fi
if [ ! -f "$MODEL_ABS_DIR/onnx/vocab.txt" ]; then
  echo ">> 警告：未找到 vocab.txt（需与 model.onnx 同目录），本地向量化将不可用" >&2
fi

# ---- 自动写入 .env 配置（开箱即用） ----
ENV_FILE="$PROJECT_ROOT/.env"
set_env() { # set_env KEY VALUE（不存在则追加，存在则覆盖该行；无 GNU sed 依赖）
  local key="$1" val="$2"
  if grep -qE "^${key}=" "$ENV_FILE" 2>/dev/null; then
    awk -v k="$key" -v v="$val" 'BEGIN{found=0}
      index($0,k"=")==1 {print k"="v; found=1; next}
      {print}
      END{if(!found) print k"="v}' "$ENV_FILE" > "$ENV_FILE.tmp" \
      && mv "$ENV_FILE.tmp" "$ENV_FILE"
  else
    echo "${key}=${val}" >> "$ENV_FILE"
  fi
}

if [ ! -f "$ENV_FILE" ] && [ -f "$PROJECT_ROOT/.env.example" ]; then
  cp "$PROJECT_ROOT/.env.example" "$ENV_FILE"
  echo ">> 已从 .env.example 创建 $ENV_FILE"
fi
touch "$ENV_FILE"

# OPS-11：MODEL_DIM 按模型系列映射（bge-base 为 768，其余按 512），不再固定 512
case "$MODEL_NAME" in
  *bge-base*) MODEL_DIM=768 ;;
  *bge-m3*)   MODEL_DIM=1024 ;;
  *)          MODEL_DIM=512 ;;
esac

# OPS-11：EMBEDDING_MODEL_PATH 写入相对 backend 工作目录的路径（后端从 backend/ 启动，
#         ../models = 项目根 models/），避免写入本机绝对路径导致换机失效。
#         用户显式指定绝对输出目录时（/ 或 ~ 开头）才保留绝对路径。
case "$OUT_DIR" in
  /*|~*) MODEL_PATH_ENV="$MODEL_ABS_DIR/onnx/model.onnx" ;;
  *)     MODEL_PATH_ENV="../$OUT_DIR/onnx/model.onnx" ;;
esac

set_env "EMBEDDING_PROVIDER" "local"
set_env "EMBEDDING_MODEL_PATH" "$MODEL_PATH_ENV"
set_env "EMBEDDING_MODEL_DIM" "$MODEL_DIM"
rm -f "$ENV_FILE.tmp"

echo ">> 完成。已自动写入 $ENV_FILE："
echo "     EMBEDDING_PROVIDER=local"
echo "     EMBEDDING_MODEL_PATH=$MODEL_PATH_ENV"
echo "     EMBEDDING_MODEL_DIM=$MODEL_DIM"
echo ">> 重启后端后，Embedding 语义维度将以本地 BGE 生效。"
echo ">> 提示：路径相对 backend 运行目录；若经 docker-compose 部署，请在 compose 环境变量中改为容器内路径。"
