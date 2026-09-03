# -*- coding: utf-8 -*-
"""上传文件误删救援：从 tg_code_unit / tg_requirement 重建被删的上传内容。

恢复范围（按项目）：
  backend/uploads/code/{projectId}/recovered/<相对路径>          —— 由 code_unit.codeContent 重建的 Java 源文件
  backend/uploads/code/{projectId}/recovered/requirements_recovered.txt —— 由 tg_requirement.original_text 重建
说明：
  - 方法体完整恢复（codeContent 为完整方法源码）；import 语句未随单元存储，重建文件中以注释占位需人工补齐
  - 字段清单单元（[字段清单]）恢复为字段声明
  - 本脚本只创建文件，不修改数据库
"""
import os
import io
from collections import defaultdict

import pymysql

conn = pymysql.connect(host="localhost", user="root", password="123456",
                       database="traceguard", charset="utf8mb4")
cur = conn.cursor()

cur.execute("SELECT id, project_name FROM tg_project ORDER BY id")
projects = cur.fetchall()

FIELD_MARKER = "字段清单"
report = []
total_files = total_units = total_reqs = 0

for pid, pname in projects:
    cur.execute("""SELECT file_path, class_name, method_name, code_content, start_line, end_line
                   FROM tg_code_unit WHERE project_id=%s AND file_path IS NOT NULL
                   AND code_content IS NOT NULL AND code_content != ''""", (pid,))
    units = cur.fetchall()
    cur.execute("""SELECT requirement_id, original_text FROM tg_requirement
                   WHERE project_id=%s ORDER BY requirement_id""", (pid,))
    reqs = cur.fetchall()
    if not units and not reqs:
        continue

    out_root = os.path.join("backend", "uploads", "code", str(pid), "recovered")
    os.makedirs(out_root, exist_ok=True)

    # ---- 重建 Java 文件 ----
    by_file = defaultdict(list)
    for fp, cls, mn, content, s, e in units:
        by_file[fp].append({"cls": cls, "mn": mn, "content": content, "start": s or 0})

    n_files = 0
    for fp, ulist in by_file.items():
        rel = fp.replace("\\", "/").lstrip("/")
        # 相对路径去掉可能存在的 code/ 前缀
        if rel.startswith("code/"):
            rel = rel[len("code/"):]
        target = os.path.join(out_root, rel)
        os.makedirs(os.path.dirname(target), exist_ok=True)
        # 包名由路径推导
        pkg_dir = os.path.dirname(rel).replace("/", ".")
        pkg = f"package {pkg_dir};" if pkg_dir and not pkg_dir.startswith(("src", ".")) else ""
        # 排序：字段清单单元最前，其余按起始行
        ulist.sort(key=lambda u: (0 if FIELD_MARKER in (u["mn"] or "") else 1, u["start"]))
        primary_cls = max({u["cls"] for u in ulist}, key=lambda c: sum(1 for u in ulist if u["cls"] == c))
        lines = []
        lines.append(f"// [救援重建 2026-09-04] 原上传文件因运维失误删除，本文件由数据库 tg_code_unit 重建。")
        lines.append(f"// import 语句未随方法单元存储，编译前请按需补齐。项目：{pname} (id={pid})")
        if pkg:
            lines.insert(2, pkg)
            lines.insert(3, "")
        lines.append(f"// 原始相对路径: {fp}")
        lines.append(f"public class {primary_cls} {{")
        for u in ulist:
            content = u["content"]
            # 字段清单单元本身是 "// 类字段/属性声明清单..." + 各字段声明；方法单元是完整方法源码
            lines.append("")
            lines.append(content)
        lines.append("}")
        with io.open(target, "w", encoding="utf-8", newline="") as f:  # newline="": 禁止平台转换，codeContent 自带 \r\n 不再叠成 \r\r\n
            f.write("\n".join(lines) + "\n")
        n_files += 1

    # ---- 重建需求文本 ----
    n_reqs = 0
    if reqs:
        req_path = os.path.join(out_root, "requirements_recovered.txt")
        with io.open(req_path, "w", encoding="utf-8", newline="") as f:
            f.write(f"# [救援重建 2026-09-04] 项目 {pname} (id={pid}) 的需求条目（由 tg_requirement 重建）\n\n")
            for rid, text in reqs:
                f.write(f"{rid}: {text}\n\n")
        n_reqs = len(reqs)

    total_files += n_files
    total_units += len(units)
    total_reqs += n_reqs
    report.append(f"{pname} (id={pid})：重建 {n_files} 个 Java 文件（{len(units)} 单元）+ {n_reqs} 条需求"
                  + ("；[注意] 无代码单元，仅有需求" if not units and reqs else ""))

with io.open(os.path.join("backend", "uploads", "code", "RECOVERY-REPORT.txt"), "w", encoding="utf-8") as f:
    f.write("上传文件误删救援报告（2026-09-04）\n")
    f.write("=" * 60 + "\n")
    f.write("背景：backend/uploads/code/* 因运维失误被整体删除；\n"
            "数据库 tg_code_unit（方法级完整源码）与 tg_requirement（需求条目）完好，据此重建。\n"
            "未随单元存储而无法逐字恢复的：import 语句、类/文件级注解、原始 Word/PDF 版式。\n"
            "24 个 B3/C1C2 测试项目的行已随清理删除，其源码均在 samples/ 与 samples/benchmark/，可重新上传。\n\n")
    f.write("\n".join(report) + "\n")
    f.write(f"\n合计：重建 {total_files} 个文件（{total_units} 单元）、{total_reqs} 条需求。\n")

print("\n".join(report))
print(f"\n合计：{total_files} 文件 / {total_units} 单元 / {total_reqs} 需求 -> backend/uploads/code/*/recovered/")
cur.close()
conn.close()
