# 性能基准测试报告

**生成时间**: 2026-08-24T23:19:43.812086400

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
| parseRequirements | 143 | 0.14 | 0.1% |
| generateFormalSpecs | 279 | 0.28 | 0.1% |
| parseCode | 4656 | 4.66 | 2.1% |
| embedSemantics | 55243 | 55.24 | 24.8% |
| runConsistencyCheck | 131990 | 131.99 | 59.3% |
| generateDefects | 30163 | 30.16 | 13.6% |
| total | 222543 | 222.54 | 100.0% |

## 4. 达标结论

**中位总耗时**: 222.543s

✅ **达标**（低于阈值 600.0s）

## 5. 瓶颈分析 Top3

1. total: 222.543s
2. runConsistencyCheck: 131.99s
3. embedSemantics: 55.243s

## 6. 优化建议

最慢阶段: total


---
**报告生成**: GAP-008 PerformanceTest
