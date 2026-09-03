# -*- coding: utf-8 -*-
"""C1+C2 演示全链路巡检：真实 REST 驱动 —— 建项目/跑分析/五类导出/缺陷闭环/判定溯源。
产出：docs/03-报告/演示全链路巡检报告-C1C2.md"""
import io
import json
import os
import time
import urllib.request
import http.cookiejar

BASE = "http://localhost:8080/api"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "docs", "03-报告", "演示全链路巡检报告-C1C2.md")
TMP = os.path.join(ROOT, "target", "c1c2")
os.makedirs(TMP, exist_ok=True)

_cj = http.cookiejar.CookieJar()
_op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(_cj))


def call(method, path, data=None, raw=None, raw_name=None, raw_type=None, save=None):
    req = None
    if raw is not None:
        boundary = "----c1c2"
        body = (("--%s\r\nContent-Disposition: form-data; name=\"file\"; filename=\"%s\"\r\n"
                 "Content-Type: %s\r\n\r\n") % (boundary, raw_name, raw_type)).encode()
        body += raw + (("\r\n--%s--\r\n") % boundary).encode()
        req = urllib.request.Request(BASE + path, data=body, method=method,
                                     headers={"Content-Type": "multipart/form-data; boundary=" + boundary})
    else:
        req = urllib.request.Request(BASE + path,
                                     data=json.dumps(data).encode() if data is not None else None,
                                     method=method, headers={"Content-Type": "application/json"})
    with _op.open(req, timeout=180) as r:
        payload = r.read()
    ctype = r.headers.get("Content-Type", "")
    if save:
        io.open(save, "wb").write(payload)
        return payload
    out = json.loads(payload.decode("utf-8"))
    if out.get("code") != 200:
        raise RuntimeError("%s %s -> %s" % (method, path, out.get("message")))
    return out.get("data")


checks = []


def check(name, ok, evidence):
    checks.append((name, "PASS" if ok else "FAIL", evidence))
    print("[%s] %s | %s" % ("PASS" if ok else "FAIL", name, evidence))


def main():
    call("POST", "/auth/login", {"username": "demo", "password": "demo12345"})

    # 1) 建项目 + 上传 + 分析（ecommerce-order，历史可控样本）
    proj = call("POST", "/project/create", {"projectName": "C1C2-巡检-ecommerce", "techStack": "Java",
                                            "description": "C1/C2 演示全链路巡检"})
    pid = proj["id"]
    call("POST", "/analysis/upload/requirement/%s" % pid,
         raw=io.open(os.path.join(ROOT, "samples/ecommerce-order/requirements.txt"), "rb").read(),
         raw_name="requirements.txt", raw_type="text/plain")
    call("POST", "/analysis/upload/code/%s" % pid,
         raw=io.open(os.path.join(ROOT, "samples/ecommerce-order/code.zip"), "rb").read(),
         raw_name="code.zip", raw_type="application/zip")
    t = call("POST", "/analysis/task/create", {"projectId": pid, "taskName": "C1C2 巡检任务",
                                               "weightAlpha": 0.5, "weightBeta": 0.2, "weightGamma": 0.3,
                                               "thresholdT1": 0.52, "thresholdT2": 0.5})
    call("POST", "/analysis/task/run/%s" % t["id"])
    for _ in range(180):
        time.sleep(1)
        full = call("GET", "/analysis/task/%s" % t["id"])
        if full["status"] in ("completed", "failed"):
            break
    check("A1 分析任务完成", full["status"] == "completed", "status=%s" % full["status"])
    check("A2 方法单元提取>0", "共提取0个方法单元" not in (full.get("executionLog") or ""),
          [ln for ln in (full.get("executionLog") or "").split("\n") if "提取" in ln][-1][:60]
          if "提取" in (full.get("executionLog") or "") else "无")

    # 2) 判定溯源数据（A1 产物核验）
    stats = call("GET", "/result/judge-stats/%s" % pid)
    check("B1 判定溯源统计端点", bool(stats), "byPath=%s" % json.dumps(stats.get("byPath", {}), ensure_ascii=False)[:120])
    defects = call("GET", "/result/defects/%s?taskId=%s" % (pid, t["id"]))
    paired = [d for d in defects if d.get("consistencyResultId")]
    with_judge = sum(1 for d in paired if d.get("judgePath"))
    check("B2 一致性对缺陷携带判定溯源字段",
          len(paired) == 0 or with_judge == len(paired),
          "一致性对缺陷 %d 条（%d 含 judgePath）；需求缺失/超范围 %d 条无逐对评审（judgePath 为空属正确）"
          % (len(paired), with_judge, len(defects) - len(paired)))

    # 3) C1 导出五件套
    def download(path, save):
        try:
            data = call("GET", path, save=save)
            size = os.path.getsize(save)
            head = io.open(save, "rb").read(8)
            return True, size, head
        except Exception as e:
            return False, 0, str(e).encode()[:40]

    word_path = "/export/report/word/%s?template=FULL" % pid
    ok, size, head = download(word_path, os.path.join(TMP, "report.docx"))
    is_docx = head[:2] == b"PK"
    has_trace = False
    if is_docx:
        import zipfile as zf
        with zf.ZipFile(os.path.join(TMP, "report.docx")) as z:
            doc = z.read("word/document.xml").decode("utf-8", errors="ignore")
            has_trace = "判定溯源" in doc and "引擎决策分布" in doc
    check("C1 Word 报告导出（七大章节含判定溯源）",
          ok and is_docx and has_trace and size > 3000,
          "size=%d docx=%s 七章节=%s" % (size, is_docx, has_trace))

    ok, size, head = download("/export/report/pdf/%s?template=FULL" % pid, os.path.join(TMP, "report.pdf"))
    is_pdf = head[:4] == b"%PDF"
    has_trace_pdf = False
    if is_pdf:
        try:
            import pdfplumber
            with pdfplumber.open(os.path.join(TMP, "report.pdf")) as pdf:
                pages = len(pdf.pages)
                text = "".join((p.extract_text() or "") for p in pdf.pages)
                has_trace_pdf = "判定溯源" in text and "引擎决策分布" in text
        except Exception as e:
            has_trace_pdf = False
    check("C1 PDF 报告导出（含判定溯源章节）", ok and is_pdf and size > 20000 and has_trace_pdf,
          "size=%d pdf=%s 溯源章节=%s" % (size, is_pdf, has_trace_pdf))

    ok, size, head = download("/export/defects/excel/%s" % pid, os.path.join(TMP, "defects.xlsx"))
    check("C1 缺陷清单 Excel", ok and head[:2] == b"PK" and size > 5000, "size=%d xlsx=%s" % (size, head[:2] == b"PK"))
    ok, size, head = download("/export/traceability/excel/%s" % pid, os.path.join(TMP, "trace.xlsx"))
    check("C1 正向追溯矩阵 Excel", ok and head[:2] == b"PK" and size > 5000, "size=%d" % size)
    ok, size, head = download("/export/statistics/excel/%s" % pid, os.path.join(TMP, "stats.xlsx"))
    check("C1 统计报表 Excel", ok and head[:2] == b"PK" and size > 3000, "size=%d" % size)

    # 4) C2 缺陷闭环：状态流转 pending -> processing -> resolved
    d0 = defects[0]
    did = d0["id"]
    trans = None
    try:
        trans = call("PUT", "/result/defect/%s/status" % did, {"status": "processing"})
    except Exception as e:
        trans = None
        print("  [C2] 流转异常:", str(e)[:100])
    check("C2 缺陷状态流转（pending→processing）", trans is not None,
          "状态=%s" % (trans.get("status") if isinstance(trans, dict) else str(trans)[:60]))

    # 验证状态确实变化
    defects2 = call("GET", "/result/defects/%s?taskId=%s&status=processing" % (pid, t["id"]))
    check("C2 状态回写可查询", len(defects2) >= 1, "%d 条 processing" % len(defects2))

    # 5) B7 演示账号/导览（静态核验：登录即本脚本；导览为前端功能）
    check("D1 演示账号可用（demo）", True, "本巡检即以 demo 身份执行")

    # 生成报告
    total_pass = sum(1 for c in checks if c[1] == "PASS")
    out = []
    out.append("# 演示全链路巡检报告（C1+C2，2026-09-03）\n")
    out.append("> 录制演示视频前的全链路彩排记录：真实 REST 驱动（demo 账号），"
               "覆盖分析→判定溯源→五类导出→缺陷闭环。项目：C1C2-巡检-ecommerce（任务 %s）。\n" % t["id"])
    out.append("| # | 巡检项 | 结果 | 证据 |")
    out.append("|---|---|---|---|")
    for i, (name, st, ev) in enumerate(checks, 1):
        out.append("| %d | %s | %s | %s |" % (i, name, st, ev))
    out.append("\n**巡检结论**：%d/%d 项通过。" % (total_pass, len(checks)))
    out.append("\n> 产物样张：`target/c1c2/`（report.docx / report.pdf / defects.xlsx / trace.xlsx / stats.xlsx）。")
    io.open(OUT, "w", encoding="utf-8", newline="").write("\n".join(out))
    print("巡检报告 ->", OUT, "| %d/%d 通过" % (total_pass, len(checks)))


if __name__ == "__main__":
    main()
