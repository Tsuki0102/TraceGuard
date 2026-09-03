# -*- coding: utf-8 -*-
"""B3 增量分析端到端量化：REST 驱动真实后端，kilo/tenk 全量 vs 增量（10%/20% 累计 30%）对比。
产物：docs/03-报告/增量分析量化报告-B3.md + b3-quant-result.json"""
import io
import json
import os
import shutil
import time
import urllib.request
import uuid
import zipfile
from datetime import datetime

BASE = "http://localhost:8080/api"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TMP = os.path.join(ROOT, "target", "b3-work")
OUT_JSON = os.path.join(ROOT, "target", "b3-quant-result.json")

import http.cookiejar
_cj = http.cookiejar.CookieJar()
_opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(_cj))


def call(method, path, data=None, headers=None, raw=None, raw_name=None, raw_type=None):
    url = BASE + path
    if raw is not None:
        boundary = uuid.uuid4().hex
        body = (("--%s\r\nContent-Disposition: form-data; name=\"file\"; filename=\"%s\"\r\n"
                 "Content-Type: %s\r\n\r\n") % (boundary, raw_name, raw_type)).encode("utf-8")
        body += raw + (("\r\n--%s--\r\n") % boundary).encode("utf-8")
        headers = {"Content-Type": "multipart/form-data; boundary=" + boundary}
        req = urllib.request.Request(url, data=body, method=method, headers=headers)
    elif data is not None:
        req = urllib.request.Request(url, data=json.dumps(data).encode("utf-8"), method=method,
                                     headers={"Content-Type": "application/json"})
    else:
        req = urllib.request.Request(url, method=method)
    with _opener.open(req, timeout=300) as r:
        payload = json.loads(r.read().decode("utf-8"))
    if payload.get("code") != 200:
        raise RuntimeError("%s %s -> %s" % (method, path, payload.get("message")))
    return payload.get("data")


def login():
    call("POST", "/auth/login", {"username": "demo", "password": "demo12345"})


def zip_tree(code_dir, zip_path):
    if os.path.exists(zip_path):
        os.remove(zip_path)
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as z:
        for root, _dirs, files in os.walk(code_dir):
            for f in files:
                full = os.path.join(root, f)
                rel = os.path.relpath(full, code_dir)
                z.write(full, rel)


def modify_tree(src_dir, work_dir, extra_files):
    """复制 src 到 work_dir，并给 extra_files（相对路径集合）各追加一行唯一注释"""
    if os.path.exists(work_dir):
        shutil.rmtree(work_dir)
    shutil.copytree(src_dir, work_dir)
    for rel in sorted(extra_files):
        full = os.path.join(work_dir, rel)
        with io.open(full, "a", encoding="utf-8") as f:
            f.write("\n// B3 QUANT MOD %s %s\n" % (uuid.uuid4().hex[:8], rel.replace("\\", "/")))
    return work_dir


def collect_java(root_dir):
    out = []
    for root, _dirs, files in os.walk(root_dir):
        for f in sorted(files):
            if f.endswith(".java"):
                full = os.path.join(root, f)
                out.append(os.path.relpath(full, root_dir))
    return out


def run_task(project_id, name):
    task = call("POST", "/analysis/task/create", {
        "projectId": project_id, "taskName": name,
        "weightAlpha": 0.5, "weightBeta": 0.2, "weightGamma": 0.3,
        "thresholdT1": 0.52, "thresholdT2": 0.5})
    tid = task["id"]
    call("POST", "/analysis/task/run/%s" % tid)
    for _ in range(240):
        time.sleep(1)
        t = call("GET", "/analysis/task/%s" % tid)
        if t["status"] in ("completed", "failed", "terminated"):
            return t
    raise RuntimeError("task %s 超时" % tid)


def elapsed(t):
    fmt = "%Y-%m-%dT%H:%M:%S"
    a = datetime.strptime(t["startTime"][:19], fmt)
    b = datetime.strptime(t["endTime"][:19], fmt)
    return round((b - a).total_seconds(), 1)


def parse_stage(log):
    lines = [ln for ln in (log or "").split("\n") if "解析" in ln or "增量" in ln]
    return lines


def scenario(bench, code_dir, req_file):
    java_files = collect_java(code_dir)
    n = len(java_files)
    n10 = max(1, n // 10)
    n30 = max(1, n // 10 * 3)
    rows = []
    print("== %s：%d 个 Java 文件（增量10%%=%d 个 / 再增20%%=%d 个）==" % (bench, n, n10, n20_ := (n30 - n10)))

    # run1 全量基线（v0）
    proj = call("POST", "/project/create", {"projectName": "B3-%s-full" % bench,
                                            "techStack": "Java", "description": "B3 全量基线"})
    pid = proj["id"]
    call("POST", "/analysis/upload/requirement/%s" % pid,
         raw=io.open(req_file, "rb").read(), raw_name="requirements.txt", raw_type="text/plain")
    zp = os.path.join(TMP, "%s-v0.zip" % bench)
    zip_tree(code_dir, zp)
    call("POST", "/analysis/upload/code/%s" % pid, raw=io.open(zp, "rb").read(),
         raw_name="code.zip", raw_type="application/zip")
    t = run_task(pid, "B3 %s full v0" % bench)
    rows.append({"bench": bench, "run": "full-v0", "mode": "全量", "changed": "0%%",
                 "elapsed_s": elapsed(t), "status": t["status"]})
    print("  full v0: %ss" % rows[-1]["elapsed_s"])

    # v1：10% 变更（同一项目，增量）
    v1 = set(java_files[:n10])
    modify_tree(code_dir, os.path.join(TMP, "%s-w1" % bench), v1)
    zp1 = os.path.join(TMP, "%s-v1.zip" % bench)
    zip_tree(os.path.join(TMP, "%s-w1" % bench), zp1)
    call("POST", "/analysis/upload/code/%s" % pid, raw=io.open(zp1, "rb").read(),
         raw_name="code.zip", raw_type="application/zip")
    t = run_task(pid, "B3 %s inc 10%%" % bench)
    rows.append({"bench": bench, "run": "inc-10%", "mode": "增量", "changed": "%d/%d (10%%)" % (n10, n),
                 "elapsed_s": elapsed(t), "status": t["status"]})
    print("  inc 10%%: %ss" % rows[-1]["elapsed_s"])

    # v2：再改 20%（累计 30%，相对 v1 增量变更 20%）
    v2extra = set(java_files[n10:n30])
    modify_tree(code_dir, os.path.join(TMP, "%s-w2" % bench), v1 | v2extra)
    zp2 = os.path.join(TMP, "%s-v2.zip" % bench)
    zip_tree(os.path.join(TMP, "%s-w2" % bench), zp2)
    call("POST", "/analysis/upload/code/%s" % pid, raw=io.open(zp2, "rb").read(),
         raw_name="code.zip", raw_type="application/zip")
    t = run_task(pid, "B3 %s inc +20%%" % bench)
    rows.append({"bench": bench, "run": "inc-cum-30%", "mode": "增量",
                 "changed": "%d/%d (20%%，累计30%%)" % (n30 - n10, n),
                 "elapsed_s": elapsed(t), "status": t["status"]})
    print("  inc +20%%: %ss" % rows[-1]["elapsed_s"])

    # 对照：新项目上传 v2 全量（同输入全量重解析）
    proj2 = call("POST", "/project/create", {"projectName": "B3-%s-fullv2" % bench,
                                             "techStack": "Java", "description": "B3 同输入全量对照"})
    pid2 = proj2["id"]
    call("POST", "/analysis/upload/requirement/%s" % pid2,
         raw=io.open(req_file, "rb").read(), raw_name="requirements.txt", raw_type="text/plain")
    call("POST", "/analysis/upload/code/%s" % pid2, raw=io.open(zp2, "rb").read(),
         raw_name="code.zip", raw_type="application/zip")
    t = run_task(pid2, "B3 %s full v2" % bench)
    rows.append({"bench": bench, "run": "full-v2", "mode": "全量(对照)", "changed": "30%% 输入",
                 "elapsed_s": elapsed(t), "status": t["status"]})
    print("  full v2: %ss" % rows[-1]["elapsed_s"])
    return rows


def main():
    os.makedirs(TMP, exist_ok=True)
    login()
    all_rows = []
    for bench in ["kilo", "tenk"]:
        code_dir = os.path.join(ROOT, "samples", "benchmark", bench, "code")
        req_file = os.path.join(ROOT, "samples", "benchmark", bench, "requirements.txt")
        all_rows += scenario(bench, code_dir, req_file)
    json.dump(all_rows, io.open(OUT_JSON, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print("\n===== B3 量化结果 =====")
    for r in all_rows:
        print("%-5s %-12s %-10s 变更=%-18s %ss" % (r["bench"], r["run"], r["mode"], r["changed"], r["elapsed_s"]))
    print("JSON ->", OUT_JSON)


if __name__ == "__main__":
    main()
