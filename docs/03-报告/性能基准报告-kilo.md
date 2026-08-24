# 性能基准测试报告

**生成时间**: 2026-08-24T21:10:50.830098400

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
- **测试模式**: Enhanced-Mode-Local-BGE

## 3. 分阶段耗时（中位数）

| 阶段 | 耗时(ms) | 耗时(s) | 占比 |
|---|---|---|---|
| parseRequirements | 69 | 0.07 | 0.5% |
| generateFormalSpecs | 178 | 0.18 | 1.3% |
| parseCode | 1449 | 1.45 | 10.8% |
| embedSemantics | 6837 | 6.84 | 51.0% |
| runConsistencyCheck | 4712 | 4.71 | 35.2% |
| generateDefects | 125 | 0.13 | 0.9% |
| total | 13394 | 13.39 | 100.0% |

## 4. 达标结论

**中位总耗时**: 13.394s

✅ **达标**（低于阈值 120.0s）

## 5. 瓶颈分析 Top3

1. total: 13.394s
2. embedSemantics: 6.837s
3. runConsistencyCheck: 4.712s

## 6. 优化建议

最慢阶段: total


---
**报告生成**: GAP-008 PerformanceTest
