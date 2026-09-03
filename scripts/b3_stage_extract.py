# -*- coding: utf-8 -*-
"""B3 阶段耗时抽取：从任务 executionLog 时间戳解析各阶段耗时。"""
import json
import re
import urllib.request
import http.cookiejar
import io
from datetime import datetime

BASE = "http://localhost:8080/api"
_cj = http.cookiejar.CookieJar()
_op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(_cj))

STAGES = [
    ("需求解析", "需求解析完成"),
    ("规约生成", "规约生成完成"),
    ("代码解析", "代码解析完成"),
    ("基础缺陷", "基础缺陷检测完成"),
    ("向量化", "向量化"),
    ("一致性校验", "一致性校验完成"),
    ("缺陷生成", "缺陷报告生成完成"),
]


def call(method, path, data=None):
    req = urllib.request.Request(BASE + path,
                                 data=json.dumps(data).encode() if data else None,
                                 method=method, headers={"Content-Type": "application/json"})
    with _op.open(req, timeout=60) as r:
        return json.loads(r.read().decode())["data"]


call("POST", "/auth/login", {"username": "demo", "password": "demo12345"})
projs = call("GET", "/project/list?pageNum=1&pageSize=50")
results = []
for p in (projs or []):
    if not p["projectName"].startswith("B3-"):
        continue
    tasks = call("GET", "/analysis/task/list/%s?pageNum=1&pageSize=10" % p["id"])
    for t in (tasks.get("records") or []):
        full = call("GET", "/analysis/task/%s" % t["id"])
        log = full.get("executionLog") or ""
        marks = []
        for ln in log.split("\n"):
            m = re.match(r"\[(.{26,32})\]", ln.strip())
            if not m:
                continue
            ts = datetime.fromisoformat(m.group(1)[:26])
            for name, done in STAGES:
                if done in ln:
                    marks.append((ts, name))
                    break
        # 阶段耗时 = 相邻标记差
        stages = {}
        prev_ts = None
        prev_name = "任务开始"
        for ts, name in marks:
            if prev_ts is not None:
                stages[prev_name] = round((ts - prev_ts).total_seconds(), 1)
            prev_ts, prev_name = ts, name
        if prev_ts is not None and full.get("endTime"):
            end = datetime.fromisoformat(full["endTime"][:26])
            stages["收尾(缺陷生成/统计)"] = round((end - prev_ts).total_seconds(), 1)
        # 增量信息
        incr = re.search(r"开始增量代码解析：变更/新增文件 (\d+) 个，移除 \d+ 个，未变更复用 (\d+) 个", log)
        results.append({
            "project": p["projectName"], "task": t["taskName"], "status": t["status"],
            "total_s": round((datetime.fromisoformat(full["endTime"][:26]) -
                              datetime.fromisoformat(full["startTime"][:26])).total_seconds(), 1),
            "incr": (incr.group(1) + "改/" + incr.group(2) + "复用") if incr else "全量",
            "stages": stages,
        })

json.dump(results, io.open("target/b3-stage-breakdown.json", "w", encoding="utf-8"),
          ensure_ascii=False, indent=1)
for r in results:
    print("%s | %s | 总=%ss | %s" % (r["project"], r["task"], r["total_s"], r["incr"]))
    for k, v in r["stages"].items():
        print("     %s: %ss" % (k, v))
