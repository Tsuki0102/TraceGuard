# 性能基准测试报告

**生成时间**: 2026-08-26T15:26:39.888670200（2026-08-27 修正：测试模式编码乱码与瓶颈 Top3 口径）

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
- **测试模式**: 增强模式（本地 BGE Embedding 开启，llm.enabled=false、embedding=local，2026-08-26 复测）

## 3. 分阶段耗时（中位数）

| 阶段 | 耗时(ms) | 耗时(s) | 占比 |
|---|---|---|---|
| parseRequirements | 342 | 0.34 | 0.1% |
| generateFormalSpecs | 310 | 0.31 | 0.1% |
| parseCode | 14304 | 14.30 | 5.6% |
| embedSemantics | 53360 | 53.36 | 21.0% |
| runConsistencyCheck | 168361 | 168.36 | 66.1% |
| generateDefects | 444 | 0.44 | 0.2% |
| total | 254625 | 254.63 | 100.0% |

## 4. 达标结论

**中位总耗时**: 254.625s

✅ **达标**（低于阈值 600.0s）

## 5. 瓶颈分析 Top3

1. runConsistencyCheck: 168.361s（66.1%）
2. embedSemantics: 53.36s（21.0%）
3. parseCode: 14.30s（5.6%）

## 6. 优化建议

最慢阶段: runConsistencyCheck（一致性计算分块缓存，见 GAP-025）


---
**报告生成**: GAP-008 PerformanceTest
