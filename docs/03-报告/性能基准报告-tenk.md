# 性能基准测试报告

**生成时间**: 2026-09-02T22:40:20.487711600

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

- **规模**: tenk
- **达标阈值**: 600.0s
- **运行次数**: 3 次（取中位数）
- **测试模式**: 规则模式（llm.enabled=false, embedding 跳过）

## 3. 分阶段耗时（中位数）

| 阶段 | 耗时(ms) | 耗时(s) | 占比 |
|---|---|---|---|
| parseRequirements | 12273 | 12.27 | 44.2% |
| generateFormalSpecs | 680 | 0.68 | 2.4% |
| parseCode | 10884 | 10.88 | 39.2% |
| embedSemantics | 3702 | 3.70 | 13.3% |
| runConsistencyCheck | 72 | 0.07 | 0.3% |
| generateDefects | 119 | 0.12 | 0.4% |
| total | 27767 | 27.77 | 100.0% |

## 4. 达标结论

**中位总耗时**: 27.767s

✅ **达标**（低于阈值 600.0s）

## 5. 瓶颈分析 Top3

1. total: 27.767s
2. parseRequirements: 12.273s
3. parseCode: 10.884s

## 6. 优化建议

最慢阶段: total


---
**报告生成**: GAP-008 PerformanceTest
