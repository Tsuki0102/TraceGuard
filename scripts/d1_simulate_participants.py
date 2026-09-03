# -*- coding: utf-8 -*-
"""D1 模拟走查数据生成器（供参考，不得作为参赛佐证）。

基座：本地 LLM 盲评真实判定（second-annotation.json，qwen2.5-coder:14b 未看第一评）。
叠加三种走查者画像的确定性扰动（固定种子可复现）：
  P1 初级：覆盖率 55%、漏检 30%、误报 10%、每对 2.5-4.0 分钟
  P2 中级：覆盖率 75%、漏检 20%、误报  6%、每对 2.0-3.2 分钟
  P3 高级：覆盖率 95%、漏检 10%、误报  3%、每对 1.5-2.4 分钟
输出：scripts/d1-human-sim/P{1,2,3}.csv（与 d1-human-baseline-template.csv 同构）
"""
import csv
import io
import json
import os
import random

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATASET = os.path.join(ROOT, "samples", "dataset")
OUT_DIR = os.path.join(ROOT, "scripts", "d1-human-sim")

PERSONAS = [
    {"id": "P1", "coverage": 0.55, "miss": 0.30, "fp": 0.10, "tmin": 2.5, "tmax": 4.0},
    {"id": "P2", "coverage": 0.75, "miss": 0.20, "fp": 0.06, "tmin": 2.0, "tmax": 3.2},
    {"id": "P3", "coverage": 0.95, "miss": 0.10, "fp": 0.03, "tmin": 1.5, "tmax": 2.4},
]

sec = json.load(open(os.path.join(DATASET, "second-annotation.json"), encoding="utf-8"))
labels = json.load(open(os.path.join(DATASET, "consistency-labels.json"), encoding="utf-8"))
verdicts = sec["verdicts"]

pairs = [p for p in labels["pairs"]
         if p.get("requirementCode") and p.get("scope") != "exclude" and p["id"] in verdicts]

os.makedirs(OUT_DIR, exist_ok=True)
summary = []
for persona in PERSONAS:
    rng = random.Random(20260903 + hash(persona["id"]) % 1000)
    covered = [p for p in pairs if rng.random() < persona["coverage"]]
    minutes = 0.0
    rows = []
    for p in covered:
        # 走查耗时
        t = rng.uniform(persona["tmin"], persona["tmax"])
        minutes += t
        # 感知基座 = LLM 盲评结论；叠加画像扰动
        second_defective = not verdicts[p["id"]]["consistent"]
        gt_defective = p["label"] == "defective"
        human_defective = second_defective
        if gt_defective and second_defective and rng.random() < persona["miss"]:
            human_defective = False          # 漏检细微缺陷
        if (not gt_defective) and (not second_defective) and rng.random() < persona["fp"]:
            human_defective = True           # 误报
        rows.append({
            "participant": persona["id"],
            "project": p["source"],
            "req_id": p["requirementCode"],
            "file": p["codeFile"],
            "method": p["method"],
            "verdict": "defective" if human_defective else "consistent",
            "defect_type": "" if not human_defective else p.get("defectType", ""),
            "start_time": "%.1f" % (minutes - t),
            "end_time": "%.1f" % minutes,
            "notes": "simulated",
        })
    out = os.path.join(OUT_DIR, persona["id"] + ".csv")
    with io.open(out, "w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=["participant", "project", "req_id", "file", "method",
                                          "verdict", "defect_type", "start_time", "end_time", "notes"])
        w.writeheader()
        w.writerows(rows)
    summary.append("%s：覆盖 %d/%d 对，累计 %.0f 分钟 -> %s" %
                   (persona["id"], len(covered), len(pairs), minutes, out))

print("\n".join(summary))
print("注意：模拟数据仅供工具链验证与流程演示，不得作为参赛佐证；参赛证据仍需真人走查。")
