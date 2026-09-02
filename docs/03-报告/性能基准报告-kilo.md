# 性能基准测试报告

**生成时间**: 2026-09-02T22:40:22.548806700

## 1. 运行环境快照

| 参数 | 值 |
|---|---|
| java.version | 21.0.2 |
| java.vendor | Oracle Corporation |
| os.name | Windows 11 |
| os.arch | amd64 |
| availableProcessors | 20 |
| maxMemoryMB | 4004 |

## 2. 测试规模

- **规模**: kilo
- **达标阈值**: 120.0s
- **运行次数**: 3 次（取中位数）
- **测试模式**: 规则模式（llm.enabled=false, embedding 跳过）

## 3. 分阶段耗时（中位数）

| 阶段 | 耗时(ms) | 耗时(s) | 占比 |
|---|---|---|---|
| parseRequirements | 60 | 0.06 | 3.0% |
| generateFormalSpecs | 98 | 0.10 | 5.0% |
| parseCode | 651 | 0.65 | 33.0% |
| embedSemantics | 1113 | 1.11 | 56.4% |
| runConsistencyCheck | 12 | 0.01 | 0.6% |
| generateDefects | 24 | 0.02 | 1.2% |
| total | 1974 | 1.97 | 100.0% |

## 4. 达标结论

**中位总耗时**: 1.974s

✅ **达标**（低于阈值 120.0s）

## 5. 瓶颈分析 Top3

1. total: 1.974s
2. embedSemantics: 1.113s
3. parseCode: 0.651s

## 6. 优化建议

最慢阶段: total


---
**报告生成**: GAP-008 PerformanceTest
