#!/usr/bin/env bash
# ============================================================================
# GAP-036：72h 无故障冒烟脚本
#
# 每 10min 提交一个样例分析任务（复用 GAP-035 的 ecommerce-order 工程），
# 采样任务状态/耗时、JVM 内存与 GC、系统 CPU/内存，写入 scripts/smoke72h/。
# 异常策略：单任务失败告警并重试一次（OPS-10：落实注释承诺的重试）；
#           网络故障/超时计入失败计数（区分错误类型，避免连续网络故障永不中断）；
#           连续 3 次失败中断并记录；系统内存使用率 > 85% 告警采样。
# 数据清理：任务名/项目名均以 smoke- 前缀打标，结束后（含中断）自动删除冒烟测试项目。
# 用法：bash scripts/smoke72h.sh [小时数=72]
# 前置：后端已启动（默认 http://localhost:8080/api），提供管理员 token。
# ============================================================================
set -euo pipefail

BASE_URL="${TG_BASE_URL:-http://localhost:8080/api}"
TOKEN="${TG_TOKEN:?请通过环境变量 TG_TOKEN 提供管理员 token}"
HOURS="${1:-72}"
INTERVAL_MIN="${TG_INTERVAL_MIN:-10}"
FAIL_LIMIT="${TG_FAIL_LIMIT:-3}"
MEM_WARN_PCT=85

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
REQ_FILE="$PROJECT_ROOT/samples/ecommerce-order/requirements.txt"
CODE_DIR="$PROJECT_ROOT/samples/ecommerce-order/code"
OUT_DIR="$SCRIPT_DIR/smoke72h"
mkdir -p "$OUT_DIR"
LOG="$OUT_DIR/smoke.log"
CSV="$OUT_DIR/smoke.csv"

# ---------- 工具函数 ----------
log() { echo "[$(date '+%F %T')] $*" | tee -a "$LOG"; }
auth() { printf 'Authorization: Bearer %s' "$TOKEN"; }

# 内存/GC 采样：优先 jstat -gc（JVM 精确），否则回退 /proc/meminfo
sample_resources() {
  local heap nonheap gc_count gc_ms cpu mem
  heap=0; nonheap=0; gc_count=0; gc_ms=0; cpu=0; mem=0
  local pid
  pid="$(pgrep -f traceguard-backend | head -n1 || true)"
  if [ -n "$pid" ] && command -v jstat >/dev/null 2>&1; then
    local out
    out="$(jstat -gc "$pid" 2>/dev/null | awk 'NR==2{print $1,$2,$3,$4,$6,$7,$8}' || true)"
    heap=$(echo "$out" | awk '{printf "%d", ($1+$2+$3+$4)/1024}')
    nonheap=$(echo "$out" | awk '{printf "%d", ($6+$7+$8)/1024}')
    gc_count=$(jstat -gc "$pid" 2>/dev/null | awk 'NR==2{printf "%d", $17+$18}' || echo 0)
    gc_ms=$(jstat -gc "$pid" 2>/dev/null | awk 'NR==2{printf "%d", $19+$20}' || echo 0)
  fi
  if [ -f /proc/loadavg ]; then cpu="$(awk '{printf "%d", $1*100}' /proc/loadavg || echo 0)"; fi
  if [ -r /proc/meminfo ]; then
    mem="$(awk '/MemTotal/{t=$2}/MemAvailable/{a=$2}END{printf "%d", (t-a)*100/t}' /proc/meminfo 2>/dev/null || echo 0)"
  fi
  echo "$heap $nonheap $gc_count $gc_ms $cpu $mem"
}

# OPS-10：解析 JSON 字段——优先 jq（精确，兼容值含逗号/引号），其次 python3，最后 sed 回退
json_field() {
  local key="$1" line
  if command -v jq >/dev/null 2>&1; then
    line="$(jq -r --arg k "$key" '.[$k] // empty' 2>/dev/null || true)"
    if [ -n "$line" ]; then printf '%s' "$line"; return 0; fi
  fi
  if command -v python3 >/dev/null 2>&1; then
    line="$(python3 -c 'import sys,json
try:
    d=json.load(sys.stdin)
    v=d.get(sys.argv[1],"")
    print(v if v is not None else "")
except Exception:
    pass' "$key" 2>/dev/null || true)"
    if [ -n "$line" ]; then printf '%s' "$line"; return 0; fi
  fi
  # 回退 sed（无 jq/python3 的极简环境；值含逗号/引号时可能截断，仅作兜底）
  sed -n "s/.*\"${key}\":\"\?\([^\",}]*\).*/\1/p" | head -n1
}

# OPS-10：清理 smoke- 前缀的测试项目（精确按记录收集的 project id 删除）
CLEAN_IDS=()
cleanup_smoke() {
  if [ ${#CLEAN_IDS[@]} -eq 0 ]; then log "无冒烟测试项目需清理"; return 0; fi
  log "清理 ${#CLEAN_IDS[@]} 个冒烟测试项目..."
  local id
  for id in "${CLEAN_IDS[@]}"; do
    curl -s --max-time 30 -X DELETE "$BASE_URL/project/$id" -H "$(auth)" >/dev/null 2>&1 || true
    log "  已删除冒烟测试项目 id=$id"
  done
  CLEAN_IDS=()
}
# 中断/退出时清理
trap 'cleanup_smoke' EXIT INT TERM

# 提交并跟踪一个任务；返回 "状态 耗时ms 项目id 任务id"；状态：completed/failed/network/timeout
run_once() {
  local name="smoke-$(date +%s)"
  local proj_id task_id status elapsed
  # OPS-10：curl 增加 --max-time 30 防止网络挂起；失败时区分 network 错误
  local create_out
  create_out="$(curl -s --max-time 30 -X POST "$BASE_URL/project/create" \
      -H "$(auth)" -H 'Content-Type: application/json' \
      -d "{\"projectName\":\"$name\",\"description\":\"72h smoke (auto-cleanup)\",\"requirementFilePath\":\"$REQ_FILE\",\"codeProjectPath\":\"$CODE_DIR\"}" 2>/dev/null || true)"
  proj_id="$(printf '%s' "$create_out" | json_field id)"
  if [ -z "$proj_id" ]; then log "ERROR 项目创建失败（网络或接口错误）"; echo "network 0"; return 0; fi
  CLEAN_IDS+=("$proj_id")
  task_id="$(curl -s --max-time 30 -X POST "$BASE_URL/analysis/task/create" \
      -H "$(auth)" -H 'Content-Type: application/json' \
      -d "{\"projectId\":$proj_id,\"taskName\":\"$name\"}" 2>/dev/null || true | json_field id)"
  if [ -z "$task_id" ]; then log "ERROR 任务创建失败(proj=$proj_id)"; echo "failed 0 $proj_id"; return 0; fi
  curl -s --max-time 30 -X POST "$BASE_URL/analysis/task/run/$task_id" -H "$(auth)" >/dev/null 2>&1 || true
  # 轮询任务状态（最多 5min）
  local status="" start_ms="$(date +%s%3N)" end_ms elapsed
  for _ in $(seq 1 60); do
    status="$(curl -s --max-time 20 "$BASE_URL/analysis/task/$task_id" -H "$(auth)" 2>/dev/null || true | json_field status)"
    if [ "$status" = "completed" ] || [ "$status" = "failed" ]; then break; fi
    sleep 5
  done
  end_ms="$(date +%s%3N)"; elapsed=$(( end_ms - start_ms ))
  if [ -z "$status" ]; then status="network"; fi
  if [ "$status" = "running" ] || [ "$status" = "pending" ] || [ "$status" = "queued" ]; then status="timeout"; fi
  echo "$status $elapsed $proj_id $task_id"
}

# ---------- 主循环 ----------
: > "$CSV"
echo "time,task,status,elapsed_ms,heap_mb,nonheap_mb,gc_count,gc_ms,cpu_pct,mem_pct" >> "$CSV"
log "72h 冒烟启动：${HOURS}h，每 ${INTERVAL_MIN}min 一次，样例=${REQ_FILE}"
[ -f "$REQ_FILE" ] || { log "ERROR 样例需求文件不存在: $REQ_FILE"; exit 1; }

start_ts="$(date +%s)"; end_ts=$(( start_ts + HOURS * 3600 )); fail_streak=0

while [ "$(date +%s)" -lt "$end_ts" ]; do
  ts="$(date '+%F %T')"
  read -r status elapsed proj_id task_id <<<"$(run_once)"
  # OPS-10：单次失败/网络故障时重试一次
  if [ "$status" != "completed" ]; then
    log "WARN 任务 $status（elapsed=${elapsed}ms），重试一次..."
    read -r status elapsed proj_id task_id <<<"$(run_once)"
  fi
  read -r heap nonheap gc_count gc_ms cpu mem <<<"$(sample_resources)"
  echo "$ts,smoke-$(date +%s),$status,$elapsed,$heap,$nonheap,$gc_count,$gc_ms,$cpu,$mem" >> "$CSV"

  if [ "$status" = "completed" ]; then
    fail_streak=0
    log "OK  任务完成 elapsed=${elapsed}ms heap=${heap}MB mem=${mem}%"
  else
    # OPS-10：failed / network / timeout 一律计入失败计数（连续网络故障不再无限重置）
    fail_streak=$(( fail_streak + 1 ))
    log "WARN 任务 $status（elapsed=${elapsed}ms）（连续失败 ${fail_streak}/${FAIL_LIMIT}）"
    if [ "$fail_streak" -ge "$FAIL_LIMIT" ]; then
      log "ERROR 连续 ${FAIL_LIMIT} 次失败（类型 $status），中断冒烟并记录"
      exit 1
    fi
  fi

  if [ "${mem:-0}" -ge "$MEM_WARN_PCT" ]; then
    log "WARN 系统内存使用率 ${mem}% 超过 ${MEM_WARN_PCT}%，已采样（见 smoke.csv）"
  fi
  sleep $(( INTERVAL_MIN * 60 ))
done

log "72h 冒烟完成：无未恢复故障，记录见 $CSV 与 $LOG（汇总回填 docs/03-报告/可靠性测试记录.md）"
