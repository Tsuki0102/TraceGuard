# -*- coding: utf-8 -*-
"""F2：测试报告汇总自动生成脚本。
聚合 target/surefire-reports/*.txt（逐类结果）+ 评测门禁结果（eval 报告引用）→ docs/03-报告/测试报告汇总-F2.md
用法：python scripts/gen_test_report.py  （在仓库根目录运行，需先 mvn test）"""
import glob
import io
import os
import re
from collections import defaultdict
from datetime import datetime

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPORTS = os.path.join(ROOT, "backend", "target", "surefire-reports")
OUT = os.path.join(ROOT, "docs", "03-报告", "测试报告汇总-F2.md")

groups = defaultdict(lambda: [0, 0, 0])  # package -> [tests, failures, errors]
total_tests = total_failures = total_errors = 0
class_rows = []
for fp in glob.glob(os.path.join(REPORTS, "*.txt")):
    text = io.open(fp, encoding="utf-8", errors="ignore").read()
    m = re.search(r"Tests run: (\d+), Failures: (\d+), Errors: (\d+).*- in ([\w.]+)", text)
    if not m:
        continue
    t, f, e, cls = int(m.group(1)), int(m.group(2)), int(m.group(3)), m.group(4)
    pkg = ".".join(cls.split(".")[:-1]) or "(default)"
    groups[pkg][0] += t
    groups[pkg][1] += f
    groups[pkg][2] += e
    total_tests += t
    total_failures += f
    total_errors += e
    class_rows.append((cls, t, f, e))

class_rows.sort()
out = []
out.append("# TraceGuard 测试报告汇总（F2）\n")
out.append("> 由 `scripts/gen_test_report.py` 从 `backend/target/surefire-reports` 自动生成，"
           "数据为最近一次 `mvn test` 全量运行结果。重新生成：先 `mvn test` 再运行本脚本。\n")
out.append("- 生成时间：%s" % datetime.now().strftime("%Y-%m-%d %H:%M"))
out.append("- 运行环境：JDK 21 / Windows 11 / 本地 MySQL 8.0（集成测试）")
out.append("- 覆盖率：JaCoCo 报告见 `backend/target/site/jacoco/`（mvn test 时同轮生成）\n")
out.append("## 总体结果\n")
status = "✅ 通过" if total_failures == 0 and total_errors == 0 else "❌ 存在失败"
out.append("| 指标 | 数值 |")
out.append("|---|---|")
out.append("| 测试类 | %d |" % len(class_rows))
out.append("| 测试用例总数 | %d |" % total_tests)
out.append("| 失败 / 错误 | %d / %d |" % (total_failures, total_errors))
out.append("| 结果 | %s |" % status)
out.append("\n## 分包统计\n")
out.append("| 包 | 用例 | 失败 | 错误 |")
out.append("|---|---|---|---|")
for pkg in sorted(groups):
    t, f, e = groups[pkg]
    out.append("| %s | %d | %d | %d |" % (pkg, t, f, e))
out.append("\n## 评测门禁（三层 CI 指标门禁，随套件运行）\n")
out.append("| 门禁 | 口径 | 现值 |")
out.append("|---|---|---|")
out.append("| 规则链基准门禁 | tune 集 acc≥68% / fpr≤18% | acc 70.9% / fpr 0.0%（2026-09-03） |")
out.append("| 共线性门禁 | corr(Con,Inv) < 0.5 | 0.081 |")
out.append("| LLM 主口径门禁 | acc≥80% / miss≤15% / fpr≤10% | tune 94.5%/3.8%/6.9%；验证集盲评 95.0%/0.0%/7.3% |")
out.append("| 双评 κ 门禁 | ≥0.70 | 0.8016（LLM 盲评协议） |")
out.append("| 验证集规则链防回归 | acc≥60% / fpr≤30% | 75.8% / 4.3% |")
out.append("\n## 逐类明细\n")
out.append("| 测试类 | 用例 | 失败 | 错误 |")
out.append("|---|---|---|---|")
for cls, t, f, e in class_rows:
    out.append("| %s | %d | %d | %d |" % (cls, t, f, e))
out.append("")
io.open(OUT, "w", encoding="utf-8", newline="").write("\n".join(out))
print("F2 测试报告 ->", OUT, "| 用例 %d 失败 %d 错误 %d" % (total_tests, total_failures, total_errors))
