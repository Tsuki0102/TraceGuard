# 性能基准工程（GAP-008）

> 支撑验收标准 5.1.1 / 7.2：千行级 ≤ 2min、万行级 ≤ 10min（规则模式，3 次中位数）。
> 基准结果见 [docs/03-报告/性能基准报告-汇总.md](../../docs/03-报告/性能基准报告-汇总.md)。

## 目录结构

| 目录 | 规模 | 说明 |
|------|------|------|
| `generator/BenchmarkProjectGenerator.java` | - | 一次性生成器（Java main，按模式化模板批量展开） |
| `kilo/` | ~1000 行、8 类、30 条需求 | 千行级基准工程（生成产物，已提交仓库可复现） |
| `tenk/` | ~10000 行、100 类、80 条需求 | 万行级基准工程（生成产物，已提交仓库可复现） |

## 复现生成

```bash
cd generator
javac -encoding UTF-8 -d gen-out BenchmarkProjectGenerator.java
java -cp gen-out BenchmarkProjectGenerator ../kilo 8 30
java -cp gen-out BenchmarkProjectGenerator ../tenk 100 80
```

## 说明

- 生成代码仅依赖 JDK 标准库（无 Spring/第三方 jar），保证 `SootCfgBuilderUtil.compileSources` 可用。
- 每类为 CRUD + 状态流转 + 循环聚合 + 批量操作的模式化方法，业务逻辑真实可编译。
- `requirements.txt` 需求条目与生成方法集合对应（创建/查询/更新/状态流转），保证需求-代码可对齐分析。
- 评测运行方式：`mvn test -Dtest=PerformanceTest`（规则模式，LLM/Embedding 关闭）。
