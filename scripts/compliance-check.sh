#!/usr/bin/env bash
# E1 公平性合规门禁：作品材料（含代码包）不得出现参赛团队学校名称等信息。
# 用法：bash scripts/compliance-check.sh   —— 命中敏感词时输出位置并以非零码退出。
# 关键词只放通用的机构名模式，避免硬编码真实校名本身造成二次泄露。
set -uo pipefail

# 校名正则（按需扩展：新增涉及校名变体时在此追加，勿写入真实校名全文）
PATTERNS=(
  "郑州轻工业"
  "ZZULI"
  "zzuli\.edu\.cn"
)

fail=0
for pat in "${PATTERNS[@]}"; do
  hits=$(grep -rInE "$pat" \
    --exclude-dir=.git --exclude-dir=node_modules --exclude-dir=dist \
    --exclude-dir=target --exclude=compliance-check.sh \
    . 2>/dev/null || true)
  if [ -n "$hits" ]; then
    echo "[compliance] 违规命中关键词 /$pat/："
    echo "$hits"
    fail=1
  fi
done

if [ "$fail" -ne 0 ]; then
  echo "[compliance] FAIL：作品材料不得出现学校名称/Logo/指导教师等标识信息（大赛公平性要求）。"
  exit 1
fi
echo "[compliance] PASS：未发现校名等标识信息残留。"
