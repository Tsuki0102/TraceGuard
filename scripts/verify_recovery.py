# -*- coding: utf-8 -*-
"""救援质量验证：逐单元检查 codeContent 是否逐字存在于重建文件中。"""
import os
import io
from collections import defaultdict

import pymysql

conn = pymysql.connect(host="localhost", user="root", password="123456",
                       database="traceguard", charset="utf8mb4")
cur = conn.cursor()
cur.execute("""SELECT cu.project_id, p.project_name, cu.file_path, cu.code_content, cu.method_name
               FROM tg_code_unit cu JOIN tg_project p ON cu.project_id = p.id
               WHERE cu.code_content IS NOT NULL AND cu.code_content != ''""")
by_file = defaultdict(list)
meta = {}
for pid, pname, fp, content, mn in cur.fetchall():
    by_file[(pid, fp)].append((content, mn))
    meta[pid] = pname

total_units = total_ok = 0
bad_files = []
for (pid, fp), units in sorted(by_file.items()):
    rel = fp.replace("\\", "/").lstrip("/")
    if rel.startswith("code/"):
        rel = rel[len("code/"):]
    target = os.path.join("backend", "uploads", "code", str(pid), "recovered", rel)
    if not os.path.exists(target):
        bad_files.append((pid, fp, "重建文件缺失"))
        total_units += len(units)
        continue
    text = io.open(target, encoding="utf-8").read()
    ok = True
    for content, mn in units:
        total_units += 1
        # 换行归一后比对
        if content.replace("\r\n", "\n") not in text.replace("\r\n", "\n"):
            ok = False
            bad_files.append((pid, fp, f"单元 {mn} 内容不完整"))
        else:
            total_ok += 1
    if not ok:
        bad_files.append((pid, fp, "该文件存在缺失单元"))

print(f"验证范围：{len(by_file)} 个文件 / {total_units} 个单元")
print(f"逐字完整：{total_ok}/{total_units}")
if bad_files:
    print("异常清单：")
    for b in bad_files[:20]:
        print("  ", b)
else:
    print("全部单元逐字完整 ✓")
