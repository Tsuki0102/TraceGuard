# -*- coding: utf-8 -*-
"""D1 人工基线对比汇总脚本。
用法：python scripts/d1_summarize.py --csv <回收CSV文件或目录> --out docs/03-报告/人工基线对比结果-D1.md
CSV 列：participant,project,req_id,file,method,verdict,defect_type,start_time,end_time,notes
"""
import argparse
import csv
import io
import glob
import json
import os
import sys
from collections import defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATASET = os.path.join(ROOT, "samples", "dataset")

# 项目 -> GT 缺陷（defect-ground-truth 的语义型缺陷，与 labels 对齐）
# 与《评测报告-综合》系统口径对齐：检出 = 该 (req, file.method) 被人工标记为对应缺陷类型


def load_gt():
    labels = json.load(open(os.path.join(DATASET, "consistency-labels.json"), encoding="utf-8"))
    gt_defects = []       # (project, req_id, file.method)
    gt_pairs = {}         # id -> pair
    for p in labels["pairs"]:
        if p.get("scope") == "exclude" or not p.get("requirementCode"):
            continue
        gt_pairs[p["id"]] = p
        if p["label"] == "defective":
            gt_defects.append((p["source"], p["requirementCode"],
                               p["codeFile"].replace(".java", "") + "." + p["method"], p.get("defectType", "")))
    return labels, gt_pairs, gt_defects


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--csv", required=True, help="人工结果 CSV 文件或目录")
    ap.add_argument("--out", default="docs/03-报告/人工基线对比结果-D1.md")
    ap.add_argument("--simulated", action="store_true",
                    help="标注输入为模拟走查数据（LLM 盲评+画像扰动），输出免责横幅")
    args = ap.parse_args()

    paths = sorted(glob.glob(os.path.join(args.csv, "*.csv"))) if os.path.isdir(args.csv) else [args.csv]
    rows = []
    for path in paths:
        with io.open(path, encoding="utf-8-sig") as f:
            rows += list(csv.DictReader(f))
    if not rows:
        print("未读取到人工记录，请检查 --csv 路径")
        sys.exit(1)

    labels, gt_pairs, gt_defects = load_gt()
    # 标注对索引：(project, req_id, file.method) -> pair
    pair_index = {}
    for p in labels["pairs"]:
        if not p.get("requirementCode") or p.get("scope") == "exclude":
            continue
        key = (p["source"], p["requirementCode"],
               p["codeFile"].replace(".java", "") + "." + p["method"])
        pair_index[key] = p

    by_participant_project = defaultdict(lambda: {"judged": 0, "defects": [], "minutes": 0.0})
    human_defect_keys = set()
    for r in rows:
        pp = r.get("participant", "").strip()
        proj = r.get("project", "").strip()
        verdict = r.get("verdict", "").strip()
        key3 = (proj, r.get("req_id", "").strip(),
                r.get("file", "").strip().replace(".java", "") + "." + r.get("method", "").strip())
        st = by_participant_project[(pp, proj)]
        st["judged"] += 1
        if verdict == "defective":
            st["defects"].append(key3)
            human_defect_keys.add((pp,) + key3)
        try:
            st["minutes"] += (float(r["end_time"]) - float(r["start_time"])) if ":" not in r["end_time"] else 0.0
        except (ValueError, KeyError):
            pass

    # 人工检出率：按参与者计算后平均（每人：命中 GT 缺陷 / 其覆盖的 GT 缺陷对）
    gt_defect_set = {(g[0], g[1], g[2]) for g in gt_defects}
    gt_all_pairs = {(p["source"], p["requirementCode"],
                     p["codeFile"].replace(".java", "") + "." + p["method"])
                    for p in labels["pairs"]
                    if p.get("requirementCode") and p.get("scope") != "exclude"}
    per_person = defaultdict(lambda: {"judged": 0, "covered_def": 0, "hit": 0,
                                      "def_verdicts": 0, "fp": 0})
    for r in rows:
        pp = r.get("participant", "").strip()
        proj = r.get("project", "").strip()
        verdict = r.get("verdict", "").strip()
        key3 = (proj, r.get("req_id", "").strip(),
                r.get("file", "").strip().replace(".java", "") + "." + r.get("method", "").strip())
        st = per_person[pp]
        st["judged"] += 1
        is_gt_def = key3 in gt_defect_set
        if is_gt_def:
            st["covered_def"] += 1
        if verdict == "defective":
            st["def_verdicts"] += 1
            if is_gt_def:
                st["hit"] += 1
            elif key3 in gt_all_pairs:
                st["fp"] += 1
    rates = []
    for pp, st in per_person.items():
        if st["covered_def"] > 0:
            rates.append({
                "pp": pp,
                "detect": st["hit"] / st["covered_def"],
                "fp": st["fp"] / max(1, st["def_verdicts"]),
                "covered_def": st["covered_def"], "hit": st["hit"], "fp_n": st["fp"],
                "def_verdicts": st["def_verdicts"],
            })
    avg_detect = sum(r["detect"] for r in rates) / max(1, len(rates))
    avg_fp = sum(r["fp"] for r in rates) / max(1, len(rates))
    human_hit = sum(r["hit"] for r in rates)
    human_covered_def = sum(r["covered_def"] for r in rates)
    human_fp = sum(r["fp_n"] for r in rates)
    human_def_verdicts = sum(r["def_verdicts"] for r in rates)

    sys_defects = sum(1 for p in labels["pairs"]
                      if p.get("requirementCode") and p.get("scope") != "exclude"
                      and p["label"] == "defective")
    out = []
    out.append("# 人工基线对比结果（D1）\n")
    if args.simulated:
        out.append("> ⚠️ **模拟数据声明**：本报告由模拟走查数据生成（LLM 盲评真实判定 + 三画像确定性扰动，"
                   "见 scripts/d1_simulate_participants.py），仅供工具链验证与流程演示，"
                   "**不得作为参赛佐证材料**；参赛证据仍需真人走查采集。\n")
    out.append("> 由 scripts/d1_summarize.py 自动汇总；人工数据采集口径见《人工基线对比实验设计-D1》。\n")
    out.append("\n| 指标 | 人工走查 | TraceGuard（LLM 主口径） |")
    out.append("|---|---|---|")
    out.append("| 参与人数/运行次数 | %d 人 | 1 次自动化运行 |" % len({r.get("participant", "") for r in rows}))
    out.append("| 判定对覆盖 | %d 条判断 | %d 对标注（in-scope）|" % (len(rows), len(pair_index)))
    out.append("| GT 缺陷检出率（人均） | **%.1f%%**（%d/%d 人·次，%d 人） | 漏检率 3.8%%（tune）/ 0.0%%（validation）|" % (
        100.0 * avg_detect, human_hit, human_covered_def, len(rates)))
    out.append("| 误报率（人均） | %.1f%%（%d/%d 人·次判缺陷） | fpr 6.9%%（tune）/ 7.3%%（validation）|" % (
        100.0 * avg_fp, human_fp, human_def_verdicts))
    # 时间（若填了分钟数值列）
    minutes = [st["minutes"] for st in by_participant_project.values() if st["minutes"] > 0]
    if minutes:
        out.append("| 平均单位走查耗时 | %.1f 分钟/项目判断记录 | 秒级（kilo 全栈 25s / tenk 223s，规则模式含向量化）|" % (
            sum(minutes) / len(minutes)))
    out.append("\n### 人工明细（按人×项目）\n")
    out.append("| 参与者 | 项目 | 判断条数 | 判缺陷数 |")
    out.append("|---|---|---|---|")
    for (pp, proj), st in sorted(by_participant_project.items()):
        out.append("| %s | %s | %d | %d |" % (pp, proj, st["judged"], len(st["defects"])))
    out.append("\n> 注：人工列数字由真实走查采集，不做估算补齐；参与者未覆盖的条目不计入检出率分母时须注明覆盖比例。")

    with io.open(args.out, "w", encoding="utf-8", newline="") as f:
        f.write("\n".join(out))
    print("报告 ->", args.out)
    print("人工判断 %d 条 / 人均检出率 %.1f%% / 人均误报率 %.1f%%" % (
        len(rows), 100.0 * avg_detect, 100.0 * avg_fp))


if __name__ == "__main__":
    main()
