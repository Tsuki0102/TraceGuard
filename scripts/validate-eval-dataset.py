#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
GAP-007 量化评测数据集治理：一致性 / 完整性校验脚本
====================================================
校验对象（samples/ 下用于量化评测的全部标注数据集）：
  - samples/dataset/consistency-labels.json   需求-代码一致性标注集（CL-xxx，M=55 in-scope）
  - samples/dataset/defect-ground-truth.json  缺陷 Ground Truth（Dxx/API-xx/EX-xx）
  - samples/dataset/detection-results.json    系统检出记录基线（逐对）
  - samples/dataset/spec-conversion-pairs.json 需求->Alloy 转换对照集（SC-xxx，N=45）
  - samples/logic-eval/samples.json           逻辑还原参考摘要集（M01-M30）

校验维度：
  1. 语法可解析、字段结构合法（JSON Schema 级）
  2. ID 唯一性、枚举值合法性（label/defectType/source/category/scope）
  3. 引用完整性（defectId -> defect-ground-truth.json；codeFile -> 样例工程代码文件）
  4. 统计自洽（各文件 summary 与真实计数一致；样本规模满足评测达标线）
  5. 交叉一致性（detection-results 与 consistency-labels 的 ID 集合一致）

用法：
  python scripts/validate-eval-dataset.py
退出码：全部通过返回 0，任一 FAIL 返回 1。
"""

import json
import os
import sys

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATASET = os.path.join(ROOT, "samples", "dataset")
SAMPLES = os.path.join(ROOT, "samples")

SOURCES = {"ecommerce-order", "api-service", "exam-system"}
# GAP-020 四类口径（一致性缺陷）+ 基础代码缺陷（GAP-007 排除口径）
FOUR_TYPES = {"需求缺失", "业务逻辑不一致", "约束条件不满足", "代码超范围实现"}
GROUND_TRUTH_TYPES = FOUR_TYPES | {"基础代码缺陷"}
CATEGORIES = {
    "simple-crud", "loop-aggregate", "multi-branch-validation",
    "exception-handling", "resource-management", "state-transition",
}

PASS, FAIL = 0, 0


def check(cond, msg):
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  [PASS] {msg}")
    else:
        FAIL += 1
        print(f"  [FAIL] {msg}")


def load(name, path):
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception as e:  # noqa: BLE001
        print(f"  [FAIL] 无法加载 {name}: {e}")
        sys.exit(1)


def assert_unique(ids, what):
    dup = sorted({x for x in ids if ids.count(x) > 1})
    check(not dup, f"{what} ID 唯一（重复: {dup if dup else '无'}）")


def assert_subset(items, allowed, what):
    bad = sorted({x for x in items if x not in allowed})
    check(not bad, f"{what} 取值合法（非法: {bad if bad else '无'}）")


def assert_summary(summary, expected, what):
    mism = {k: (summary.get(k), v) for k, v in expected.items() if summary.get(k) != v}
    check(not mism, f"{what} summary 与真实计数一致（不一致项: {mism if mism else '无'}）")


def code_file_exists(source, code_file):
    """样例代码为 Java 包结构（code/com/...），递归查找文件名即可。"""
    code_root = os.path.join(SAMPLES, source, "code")
    if not os.path.isdir(code_root):
        return False
    for _, _, files in os.walk(code_root):
        if code_file in files:
            return True
    return False


def main():
    print("===== 1/5 samples/dataset/consistency-labels.json =====")
    labels = load("consistency-labels", os.path.join(DATASET, "consistency-labels.json"))
    pairs = labels.get("pairs", [])
    check(isinstance(pairs, list) and len(pairs) > 0, f"pairs 非空（共 {len(pairs)} 条）")
    ids = [p.get("id", "") for p in pairs]
    assert_unique(ids, "CL 标注对")
    check(all(p.get("source") in SOURCES for p in pairs),
          f"source 枚举合法（合法集: {sorted(SOURCES)}）")
    check(all(p.get("label") in {"consistent", "defective"} for p in pairs),
          "label 枚举合法（consistent/defective）")
    bad_type = [
        p.get("id") for p in pairs
        if p.get("label") == "defective" and p.get("defectType") not in FOUR_TYPES
    ]
    check(not bad_type, f"defective 对的 defectType 为四类口径（非法: {bad_type if bad_type else '无'}）")
    consistent_with_type = [
        p.get("id") for p in pairs
        if p.get("label") == "consistent" and p.get("defectType")
    ]
    check(not consistent_with_type,
          f"consistent 对不应携带 defectType（异常: {consistent_with_type if consistent_with_type else '无'}）")
    bad_scope = [p.get("id") for p in pairs if p.get("scope") not in (None, "exclude")]
    check(not bad_scope, f"scope 枚举合法（仅 exclude 可设；异常: {bad_scope if bad_scope else '无'}）")

    # 引用完整性：defectId -> ground truth
    gt = load("defect-ground-truth", os.path.join(DATASET, "defect-ground-truth.json"))
    gt_by_id = {d["id"]: d for d in gt.get("defects", [])}
    bad_ref = []
    for p in pairs:
        did = p.get("defectId")
        if not did:
            continue
        d = gt_by_id.get(did)
        if d is None:
            bad_ref.append(f"{p['id']}->{did}(不存在)")
        elif d.get("project") != p.get("source"):
            bad_ref.append(f"{p['id']}->{did}(工程不一致)")
    check(not bad_ref, f"defectId 引用完整且工程一致（问题: {bad_ref if bad_ref else '无'}）")

    # codeFile 存在性
    missing_file = [
        f"{p['id']}->{p['source']}/{p['codeFile']}" for p in pairs
        if not code_file_exists(p.get("source", ""), p.get("codeFile", ""))
    ]
    check(not missing_file, f"codeFile 指向样例工程真实文件（缺失: {missing_file if missing_file else '无'}）")

    # 规模达标线：in-scope（requirementCode 非空 且 非 exclude）>= 40
    in_scope = [p for p in pairs if p.get("requirementCode") and p.get("scope") != "exclude"]
    check(len(in_scope) >= 40, f"in-scope 对齐对 M={len(in_scope)} >= 40（评测达标线）")

    # summary 自洽（口径：consistent/defective 均指 in-scope 对齐对；exclude 与范围外单列）
    n_consistent = sum(1 for p in in_scope if p.get("label") == "consistent")
    n_defective = sum(1 for p in in_scope if p.get("label") == "defective")
    n_excl = sum(1 for p in pairs if p.get("scope") == "exclude")
    n_out = len(pairs) - len(in_scope) - n_excl
    by_source = {s: sum(1 for p in pairs if p.get("source") == s) for s in SOURCES}
    assert_summary(labels.get("summary", {}), {
        "total": len(pairs), "inScope": len(in_scope),
        "consistent": n_consistent, "defective": n_defective,
        "excluded_scope": n_excl, "by_source": by_source,
    }, "consistency-labels")
    sum_has_out = "out_of_scope" in labels.get("summary", {})
    if sum_has_out:
        assert_summary(labels["summary"], {"out_of_scope": n_out}, "consistency-labels")
    check(len(pairs) == n_consistent + n_defective + n_excl + n_out,
          f"不变量 total = in-scope(consistent+defective) + excluded_scope + out_of_scope "
          f"（{len(pairs)} = {n_consistent}+{n_defective}+{n_excl}+{n_out}）")

    print("\n===== 2/5 samples/dataset/defect-ground-truth.json =====")
    defs = gt.get("defects", [])
    check(isinstance(defs, list) and len(defs) > 0, f"defects 非空（共 {len(defs)} 条）")
    d_ids = [d.get("id", "") for d in defs]
    assert_unique(d_ids, "缺陷")
    check(all(d.get("project") in SOURCES for d in defs), "project 枚举合法")
    assert_subset([d.get("type") for d in defs], GROUND_TRUTH_TYPES, "缺陷 type")
    missing_file = [
        f"{d['id']}->{d['project']}/{d['file']}" for d in defs
        if not code_file_exists(d.get("project", ""), d.get("file", ""))
    ]
    check(not missing_file, f"缺陷文件存在性（缺失: {missing_file if missing_file else '无'}）")
    by_project = {s: sum(1 for d in defs if d.get("project") == s) for s in SOURCES}
    by_type = {}
    for d in defs:
        by_type[d["type"]] = by_type.get(d["type"], 0) + 1
    assert_summary(gt.get("summary", {}), {
        "total": len(defs), "by_project": by_project, "by_type": by_type,
    }, "defect-ground-truth")

    print("\n===== 3/5 samples/dataset/detection-results.json =====")
    det = load("detection-results", os.path.join(DATASET, "detection-results.json"))
    results = det.get("results", [])
    check(isinstance(results, list) and len(results) > 0, f"results 非空（共 {len(results)} 条）")
    det_ids = [r.get("id", "") for r in results]
    assert_unique(det_ids, "检出记录")
    # 与 consistency-labels 全集对齐（含 exclude 与范围外对）
    label_ids = set(ids)
    det_id_set = set(det_ids)
    check(label_ids == det_id_set,
          f"检出记录覆盖全部标注对（缺: {sorted(label_ids - det_id_set)[:10] if label_ids - det_id_set else '无'}；多余: {sorted(det_id_set - label_ids)[:10] if det_id_set - label_ids else '无'}）")
    check(all(isinstance(r.get("detected"), bool) for r in results), "detected 为布尔值")
    bad_dt = [
        r["id"] for r in results
        if r.get("detected") and r.get("detectedType") and r["detectedType"] not in FOUR_TYPES
    ]
    check(not bad_dt, f"detectedType 为四类口径（非法: {bad_dt if bad_dt else '无'}）")

    print("\n===== 4/5 samples/dataset/spec-conversion-pairs.json =====")
    spec = load("spec-conversion-pairs", os.path.join(DATASET, "spec-conversion-pairs.json"))
    sc_pairs = spec.get("pairs", [])
    check(isinstance(sc_pairs, list) and len(sc_pairs) > 0, f"pairs 非空（共 {len(sc_pairs)} 条）")
    sc_ids = [p.get("id", "") for p in sc_pairs]
    assert_unique(sc_ids, "SC 转换对")
    check(all(p.get("source") in SOURCES for p in sc_pairs), "source 枚举合法")
    check(all(p.get("referenceAlloy") for p in sc_pairs), "referenceAlloy 非空")
    check(all(isinstance(p.get("keyElements"), list) and p.get("keyElements") for p in sc_pairs),
          "keyElements 非空数组")
    check(40 <= len(sc_pairs) <= 60, f"样本数 N={len(sc_pairs)} 落在 40~60 达标区间")
    sc_by_source = {s: sum(1 for p in sc_pairs if p.get("source") == s) for s in SOURCES}
    assert_summary(spec.get("summary", {}), {
        "total": len(sc_pairs), "by_source": sc_by_source,
    }, "spec-conversion-pairs")

    print("\n===== 5/5 samples/logic-eval/samples.json =====")
    logic = load("logic-eval", os.path.join(SAMPLES, "logic-eval", "samples.json"))
    check(isinstance(logic, list) and len(logic) > 0, f"逻辑还原样本非空（共 {len(logic)} 条）")
    lg_ids = [s.get("id", "") for s in logic]
    assert_unique(lg_ids, "逻辑还原样本")
    check(all(s.get("file") for s in logic), "file 非空")
    missing_file = [
        f"{s['id']}->{s['file']}" for s in logic
        if not os.path.isfile(os.path.join(SAMPLES, "logic-eval", s.get("file", "")))
    ]
    check(not missing_file, f"方法文件存在性（缺失: {missing_file if missing_file else '无'}）")
    assert_subset([s.get("category") for s in logic], CATEGORIES, "category")
    check(all(s.get("reference") for s in logic), "reference 参考摘要非空")
    check(len(logic) == 30, f"样本数=30（实际 {len(logic)}）")

    print(f"\n===== 校验汇总：PASS={PASS}，FAIL={FAIL} =====")
    print(f"in-scope 对齐对 M={len(in_scope)}（目标 >=40） | 转换对 N={len(sc_pairs)}（目标 40~60） | 逻辑还原样本={len(logic)}")
    if FAIL:
        print("存在失败项，请修复数据集后重跑。")
        sys.exit(1)
    print("数据集内部一致性校验全部通过。")


if __name__ == "__main__":
    main()
