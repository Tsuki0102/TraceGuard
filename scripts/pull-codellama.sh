#!/usr/bin/env bash
# GAP-043 / GAP-046：本地拉起 Ollama + CodeLlama，用于「CodeLlama 真集成」验证。
# 前置：已安装 Ollama（https://ollama.com）。
# 用法：
#   chmod +x pull-codellama.sh
#   ./pull-codellama.sh            # 拉取并启动默认 7b
#   ./pull-codellama.sh 13b        # 指定模型规模
#   OLLAMA_URL=http://127.0.0.1:11434 ./pull-codellama.sh   # 指定 Ollama 服务地址
set -e

MODEL="${1:-codellama:7b}"
# OPS-11：Ollama 地址参数化（默认 localhost:11434），可通过环境变量覆盖
OLLAMA_URL="${OLLAMA_URL:-http://localhost:11434}"

echo "[1/3] 拉取 ${MODEL} ..."
ollama pull "${MODEL}"

echo "[2/3] 确保 Ollama 服务已启动（OpenAI 兼容端点 ${OLLAMA_URL}/v1）..."
# 若本地无运行中的 ollama serve，则后台拉起
# OPS-11：curl 增加 -fsS --max-time 30，失败即报错中断（原脚本失败不中断继续）
if ! curl -fsS --max-time 30 -o /dev/null "${OLLAMA_URL}/api/tags"; then
  echo "  启动 ollama serve ..."
  nohup ollama serve > /tmp/ollama.log 2>&1 &
  sleep 5
  # 启动后重试一次，仍失败则明确报错
  if ! curl -fsS --max-time 30 -o /dev/null "${OLLAMA_URL}/api/tags"; then
    echo "错误：Ollama 服务未就绪（${OLLAMA_URL}），请检查 /tmp/ollama.log" >&2
    exit 1
  fi
fi

echo "[3/3] 自检：调用 ${MODEL} 生成一段文本 ..."
curl -fsS --max-time 120 "${OLLAMA_URL}/api/generate" -d "{
  \"model\": \"${MODEL}\",
  \"prompt\": \"Say OK in one word.\",
  \"stream\": false
}" | head -c 300
echo

echo ""
echo "CodeLlama 已就绪。运行评测演示真集成（LangChain 引擎 + CodeLlama）："
echo "  cd backend"
echo "  LLM_PROVIDER=codellama LLM_ENGINE=langchain mvn -B test -Dtest=DefectDetectionEvalTest"
echo "或仅 self 引擎（OpenAI 兼容客户端直接调用 CodeLlama）："
echo "  LLM_PROVIDER=codellama LLM_ENGINE=self mvn -B test -Dtest=DefectDetectionEvalTest"
