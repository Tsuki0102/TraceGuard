# -*- coding: utf-8 -*-
"""TraceGuard LLM 判定离线实验台（FUN-04b）。

对 eval-pairs.json（ThresholdCalibrationEvalTest -Dgap046.dump 导出的 55 个标注对快照）
逐对调用本地 ollama qwen2.5-coder:14b，对比不同判定提示词策略的
缺陷检测三指标（准确率>=80%、漏检率<=15%、误报率<=10%，SRS FR-CODE-004 口径），

用法：
    python lab.py --pairs ../../eval-pairs.json --variants v0,v0t0,strict --votes 1 --out result.json
"""
import argparse
import json
import re
import sys
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed

OLLAMA_URL = "http://localhost:11434/v1/chat/completions"
MODEL = "qwen2.5-coder:14b"
CODE_TRUNCATE = 2500
DEFECT_TYPES = ["业务逻辑不一致", "约束条件不满足", "需求缺失", "代码超范围实现"]


# ---------- 提示词构件（常量库见 prompts.py，与生产判定器同源） ----------

from prompts import (SYS_V0, USER_TMPL, STRICT_EXTRA, SCOPE_ANCHOR_EXTRA,
                     SCOPE_ANCHOR_EXTRA_FULL, SLIM_ANCHOR, FEWSHOT_ANCHOR,
                     FEWSHOT_SLIM, FEWSHOT_EXTRA)


_FILE_CACHE = {}

RAW_ROOT = "../../samples"


def raw_class_text(source, cls):
    """从样例工程原始源码读取类文件文本（与生产 JudgeContextScanner 同口径：正则扫描）。"""
    import os
    key = (source, cls)
    if key in _FILE_CACHE:
        return _FILE_CACHE[key]
    root = os.path.join(RAW_ROOT, source, "code")
    hit = None
    for dirpath, _dirs, files in os.walk(root):
        for fn in files:
            if fn == cls + ".java":
                hit = os.path.join(dirpath, fn)
                break
        if hit:
            break
    text = open(hit, encoding="utf-8", errors="ignore").read() if hit else ""
    _FILE_CACHE[key] = text
    return text


CONST_RE = re.compile(r"^\s{4}(private|public|protected)\s+(static\s+)?(final\s+)?[\w<>\[\], .]+?\s+\w+(\s*=\s*[^;]+)?;\s*$")
METHOD_RE = re.compile(r"^\s{4}(public|protected|private)\s+[\w<>\[\]]+\s+(\w+)\(")
MODIFIER_WORDS = {"public", "private", "protected", "static", "final", "synchronized", "abstract", "default"}
CTRL_KEYWORDS = {"if", "for", "while", "switch", "catch", "return", "new", "try", "else", "do"}


def scan_class_evidence(source, cls):
    """扫描类级常量与方法签名（Java 版 JudgeContextScanner 的镜像实现）。"""
    text = raw_class_text(source, cls)
    consts, others = [], []
    for line in text.splitlines():
        s = line.strip()
        if not s or s.startswith(("@", "//", "*", "/*")):
            continue
        m = CONST_RE.match(line)
        if m and "Logger" not in s:
            consts.append(s)
            continue
        mm = METHOD_RE.match(line)
        if mm:
            name = mm.group(2)
            if name in CTRL_KEYWORDS or name == cls:
                continue
            cut = min(len(s), s.find("{") if s.find("{") > 0 else len(s))
            sig = s[:cut].strip()
            if sig.endswith(")") or "(" in sig:
                others.append(sig)
    return consts[:15], others[:60]


def sibling_context(pair, classes):
    """构造同类常量定义 + 其他方法签名清单（基于原始源码扫描，判定他人职责/数值核对的实证依据）。"""
    cls = pair.get("class")
    if not cls:
        return ""
    consts, others = scan_class_evidence(pair.get("source") or "", cls)
    method = pair.get("method")
    others = [o for o in others
              if not any(part == method for part in o.split("(")[0].split())]
    sections = []
    if consts:
        sections.append("【同类字段/常量定义（数值核对证据）】\n" + "\n".join("- " + c for c in consts))
    if others:
        sections.append("【同类其他方法（分工清单，判定他人职责的依据）】\n"
                        + "\n".join("- " + o for o in others))
    if not sections:
        return ""
    return "\n\n".join(sections) + "\n\n"


def build_messages(variant, pair, classes=None):
    user = USER_TMPL.format(req=pair["req"], code=trunc(pair["code"]), sem=pair["sem"],
                            con=pair["con"], inv=pair["inv"], tot=pair["total"])
    ctx = sibling_context(pair, classes)
    if ctx:
        user = user.replace("得分参考：", ctx + "得分参考：")
    system = SYS_V0
    if variant in ("strict", "strict_num"):
        system = SYS_V0.replace("【输出】", STRICT_EXTRA + "\n【输出】")
    if variant in ("anchor", "anchor_fewshot", "anchor_ctx"):
        system = SYS_V0.replace("【输出】", SCOPE_ANCHOR_EXTRA + "\n【输出】")
    if variant == "anchor_fewshot":
        system = system.replace("【输出】", FEWSHOT_EXTRA + "\n【输出】")
    if variant == "ctx3":
        system = SYS_V0.replace("【输出】", SCOPE_ANCHOR_EXTRA_FULL + "\n【输出】")
    if variant in ("ctx3f",):
        system = SYS_V0.replace("【输出】", SCOPE_ANCHOR_EXTRA_FULL + FEWSHOT_ANCHOR + "\n【输出】")
    if variant in ("ancov1", "ancov1_fs"):
        system = SYS_V0.replace("【输出】", SCOPE_ANCHOR_EXTRA + "\n【输出】")
    if variant == "ancov1_fs":
        system = system.replace("【输出】", FEWSHOT_SLIM + "\n【输出】")
    if variant in ("slim", "slim_f"):
        system = SYS_V0.replace("【输出】", SLIM_ANCHOR + "\n【输出】")
    if variant == "slim_f":
        system = system.replace("【输出】", FEWSHOT_SLIM + "\n【输出】")
    return [{"role": "system", "content": system}, {"role": "user", "content": user}]


def trunc(s):
    s = s or ""
    return s if len(s) <= CODE_TRUNCATE else s[:CODE_TRUNCATE] + "..."


# ---------- 两阶段判定（FUN-04b：感知 -> 裁决） ----------

SYS_STAGE1 = (
    "你是需求分析助手。给你一条需求、一个代码方法、以及该类的方法分工清单与常量定义。"
    "任务：把需求拆成具体义务条目，并逐条判断归属。只输出JSON，不要解释：\n"
    "{\"duty\":\"当前方法自身职责一句话\","
    "\"clauses\":[{\"text\":\"义务条目\",\"owner\":\"self|other|none\",\"evidence\":\"代码/清单中的证据或空\",\"ok\":true}]}\n"
    "owner 含义：self=本方法应承担；other=同类其他方法或其他环节承担；none=需求未实际要求/防御性推断。"
    "clauses 需覆盖全部显式要求（校验、数值边界、状态流转、比较方向、返回结果），3~8 条为宜。"
)

SYS_STAGE2 = (
    "你是需求-代码一致性审查专家。基于给定的《义务归属清单》做最终裁决。\n"
    "【裁决规则】①只有 owner=self 且 ok=false 的条目才允许判不一致；②不一致须指明唯一主责条款；"
    "③类常量数值不符只归属于使用它执行检查的方法，辅助方法(清理/查询/汇总)引用同一常量不算缺陷；"
    "④默认结论是 consistent=true。\n"
    "【输出】严格只输出JSON：{\"consistent\":true或false,\"defectType\":\"业务逻辑不一致|约束条件不满足"
    "|需求缺失|代码超范围实现\",\"reason\":\"≤20字\"}，defectType仅在不一致时填四类之一。"
)


def build_stage1(pair, classes=None):
    ctx = sibling_context(pair, classes)
    user = f"需求：{pair['req']}\n\n当前方法代码：\n{trunc(pair['code'])}\n\n{ctx}请输出归属清单JSON。"
    return [{"role": "system", "content": SYS_STAGE1}, {"role": "user", "content": user}]


def build_stage2(pair, worksheet, classes=None):
    ctx = sibling_context(pair, classes)
    ws = json.dumps(worksheet, ensure_ascii=False)
    user = (f"需求：{pair['req']}\n\n当前方法代码：\n{trunc(pair['code'])}\n\n{ctx}"
            f"义务归属清单：{ws}\n\n得分参考：综合={pair['total']:.2f}\n请输出最终裁决JSON。")
    return [{"role": "system", "content": SYS_STAGE2}, {"role": "user", "content": user}]


def parse_worksheet(raw):
    node = extract_json(raw)
    if not isinstance(node, dict):
        return None
    clauses = node.get("clauses")
    if not isinstance(clauses, list):
        return None
    norm = []
    for c in clauses[:12]:
        if not isinstance(c, dict):
            continue
        norm.append({"text": str(c.get("text", ""))[:80],
                     "owner": str(c.get("owner", "none")),
                     "evidence": str(c.get("evidence", ""))[:60],
                     "ok": bool(c.get("ok", True))})
    if not norm:
        return None
    return {"duty": str(node.get("duty", ""))[:60], "clauses": norm}


def two_stage_call(pair, temperature, classes=None):
    r1 = call_llm(build_stage1(pair, classes), temperature)
    ws = parse_worksheet(r1)
    if ws is None:
        # 感知失败退化为单阶段（保持可用性）
        raw = call_llm(build_messages("anchor_ctx", pair, classes), temperature)
        v = parse_verdict(raw)
        return v, "(stage1失败退化)"
    r2 = call_llm(build_stage2(pair, ws, classes), temperature)
    v = parse_verdict(r2)
    return v, json.dumps(ws, ensure_ascii=False)

def call_llm(messages, temperature=None, retries=2):
    body = {"model": MODEL, "messages": messages, "stream": False}
    if temperature is not None:
        body["temperature"] = temperature
    last_err = None
    for _ in range(retries + 1):
        try:
            req = urllib.request.Request(
                OLLAMA_URL, data=json.dumps(body).encode("utf-8"),
                headers={"Content-Type": "application/json"})
            with urllib.request.urlopen(req, timeout=180) as resp:
                data = json.loads(resp.read().decode("utf-8"))
            return data["choices"][0]["message"]["content"]
        except Exception as e:  # noqa: BLE001
            last_err = e
            time.sleep(2)
    raise RuntimeError(f"llm call failed: {last_err}")


def extract_json(text):
    """与 LlmChain.parseJson 同口径：整体解析失败则截取首个平衡 {...}"""
    try:
        return json.loads(text.strip())
    except Exception:
        pass
    start = text.find("{")
    if start < 0:
        return None
    depth = 0
    for i in range(start, len(text)):
        c = text[i]
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                try:
                    return json.loads(text[start:i + 1])
                except Exception:
                    return None
    return None


def normalize_type(t):
    t = t or ""
    if "需求缺失" in t:
        return "需求缺失"
    if "超范围" in t:
        return "代码超范围实现"
    if any(k in t for k in ["约束", "不变量", "校验", "空", "异常", "参数"]):
        return "约束条件不满足"
    if any(k in t for k in ["业务逻辑", "逻辑", "行为", "实现"]):
        return "业务逻辑不一致"
    return ""


def parse_verdict(raw):
    """与 ConsistencyJudge.parse 同置信度门槛：consistent 必须为布尔；不一致必须带类型。"""
    node = extract_json(raw)
    if node is None or not isinstance(node, dict):
        return None
    consistent = node.get("consistent")
    if not isinstance(consistent, bool):
        return None
    dtype = normalize_type(str(node.get("defectType", "")))
    reason = str(node.get("reason", ""))
    if not consistent and not dtype:
        return None
    return {"consistent": consistent, "defectType": dtype, "reason": reason}


def const_names(source, cls):
    consts, _o = scan_class_evidence(source or "", cls or "")
    names = []
    for c in consts:
        m = re.search(r"(\w+)\s*=", c)
        if m:
            names.append(m.group(1))
    return names


def owner_filter(pair, verdict):
    """FUN-04b 数值归属过滤：判不一致若仅因类常量数值，而该常量名不在本方法代码中出现，
    则归属错误，改判一致（只放松，不收紧）。"""
    if verdict is None or verdict.get("consistent", True):
        return verdict
    reason = verdict.get("reason") or ""
    body = pair.get("code") or ""
    for n in const_names(pair.get("source"), pair.get("class")):
        if n in reason and n not in body:
            import copy
            v2 = dict(verdict)
            v2["consistent"] = True
            v2["reason"] = "(常量归因修正:" + n + ")"
            v2["defectType"] = ""
            return v2
    return verdict


def one_call(pair, variant, temperature, classes=None):
    if variant.startswith("two"):
        v, ws = two_stage_call(pair, temperature, classes)
        if variant.endswith("_f1"):
            v = owner_filter(pair, v)
        return {"ok": v is not None, "raw": ws, "verdict": v}
    msgs = build_messages(variant, pair, classes)
    raw = call_llm(msgs, temperature)
    v = parse_verdict(raw)
    return {"ok": v is not None, "raw": raw, "verdict": v}


def majority(votes, fallback_detected):
    """多数投票：一致票占多 -> 一致；不一致占多 -> 不一致（类型取首个有效）。平票按规则兜底。"""
    ok_votes = [v for v in votes if v["ok"]]
    if not ok_votes:
        return {"detected": fallback_detected, "defectType": "", "reason": "(all-invalid)"}, len(ok_votes)
    inconsistent = [v["verdict"] for v in ok_votes if not v["verdict"]["consistent"]]
    detected = len(inconsistent) * 2 > len(ok_votes)
    if detected:
        d = inconsistent[0]
        return {"detected": True, "defectType": d["defectType"], "reason": d["reason"]}, len(ok_votes)
    if not detected and len(inconsistent) * 2 == len(ok_votes):
        # 平票：对含一致票的保护性兜底 -> 判一致（抑制误报优先，SRS 误报目标更紧）
        return {"detected": False, "defectType": "", "reason": "tie->consistent"}, len(ok_votes)
    return {"detected": False, "defectType": "", "reason": ok_votes[0]["verdict"]["reason"]}, len(ok_votes)


# ---------- 指标 ----------

def metrics(rows):
    tp = sum(1 for r in rows if r["truth"] and r["detected"])
    fp = sum(1 for r in rows if not r["truth"] and r["detected"])
    fn = sum(1 for r in rows if r["truth"] and not r["detected"])
    tn = sum(1 for r in rows if not r["truth"] and not r["detected"])
    acc = (tp + tn) / max(1, tp + fp + fn + tn)
    miss = fn / max(1, tp + fn)
    fpr = fp / max(1, fp + tn)
    return {"tp": tp, "fp": fp, "fn": fn, "tn": tn,
            "accuracy": round(acc, 4), "miss": round(miss, 4), "fpr": round(fpr, 4),
            "pass": acc >= 0.80 and miss <= 0.15 and fpr <= 0.10}


def run_variant(pairs, name, votes, temperatures, workers=4, classes=None):
    jobs = []
    with ThreadPoolExecutor(max_workers=workers) as ex:
        for p in pairs:
            fallback = p["total"] < 0.46  # 规则链路标定阈值兜底（与生产融合策略一致）
            for k in range(votes):
                t = temperatures[k % len(temperatures)]
                jobs.append((p, ex.submit(one_call, p, name, t, classes)))
        results = {}
        n_ok = 0
        for p, fut in jobs:
            r = fut.result()
            results.setdefault(p["id"], []).append(r)
    rows, detail = [], []
    for p in pairs:
        votes_r = results[p["id"]]
        dec, n_ok_v = majority(votes_r, p["total"] < 0.46)
        n_ok += 1 if n_ok_v > 0 else 0
        rows.append({"id": p["id"], "truth": p["label"] == "defective",
                     "detected": dec["detected"], "defectType": dec["defectType"],
                     "reason": dec["reason"]})
        detail.append({"id": p["id"], "label": p["label"], "decided": dec,
                       "votes": [v["verdict"] for v in votes_r]})
    m = metrics(rows)
    m.update({"variant": name, "votes": votes, "llm_ok_pairs": n_ok, "pairs": len(rows)})
    return m, detail


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pairs", default="../../eval-pairs.json")
    ap.add_argument("--variants", default="v0t0")
    ap.add_argument("--votes", type=int, default=1)
    ap.add_argument("--temps", default="0.0")
    ap.add_argument("--out", default="")
    args = ap.parse_args()

    with open(args.pairs, encoding="utf-8") as f:
        doc = json.load(f)
    pairs = doc["pairs"]
    classes = doc.get("classes")
    variants = [v.strip() for v in args.variants.split(",") if v.strip()]
    temps = [float(t) for t in args.temps.split(",")]

    summary = {}
    details = {}
    for name in variants:
        t0 = time.time()
        m, detail = run_variant(pairs, name, args.votes, temps, classes=classes)
        wall = round(time.time() - t0, 1)
        m["wall_sec"] = wall
        summary[name] = m
        details[name] = detail
        print(f"[{name}] acc={m['accuracy']:.1%} miss={m['miss']:.1%} fpr={m['fpr']:.1%} "
              f"TP={m['tp']} FP={m['fp']} FN={m['fn']} TN={m['tn']} pass={m['pass']} ({wall}s)")

    print("\nFP/FN 明细：")
    for name in variants:
        print(f"-- {name}")
        for d in details[name]:
            truth_defective = d["label"] == "defective"
            det = d["decided"]["detected"]
            tag = "FP" if (not truth_defective and det) else ("FN" if (truth_defective and not det) else None)
            if tag:
                print(f"  {tag} {d['id']}: type={d['decided']['defectType']} reason={d['decided']['reason']}")

    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            json.dump({"summary": summary, "details": details}, f, ensure_ascii=False, indent=2)
        print(f"\n已写入 {args.out}")


if __name__ == "__main__":
    sys.exit(main())
