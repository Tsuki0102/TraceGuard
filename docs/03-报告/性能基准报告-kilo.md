# 性能基准测试报告

**生成时间**: 2026-08-27T01:16:19.083335300（2026-08-27 补充：规则模式历史对照与 LLM 模式采集说明）

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
- **运行次数**: 1 次（LLM 数据采集模式，`-Dperf.runs=1`；规则模式历史批次为 3 次取中位数 5.086s，见汇总报告第 3 节）
- **测试模式**: LLM enhanced mode (local qwen2.5-coder:14b GPU, single-run data collection)

## 3. 分阶段耗时（中位数）

| 阶段 | 耗时(ms) | 耗时(s) | 占比 |
|---|---|---|---|
| parseRequirements | 666262 | 666.26 | 18.3% |
| generateFormalSpecs | 790729 | 790.73 | 21.7% |
| parseCode | 1445934 | 1445.93 | 39.6% |
| embedSemantics | 12760 | 12.76 | 0.3% |
| runConsistencyCheck | 425749 | 425.75 | 11.7% |
| generateDefects | 305394 | 305.39 | 8.4% |
| total | 3646879 | 3646.88 | 100.0% |

## 4. 达标结论

**本次 LLM 全链路增强实测耗时**: 3646.879s

❌ 超过规则模式达标阈值 120.0s（差距 3526.879s）——如实记录：LLM 全链路逐条增强（对每条需求/方法/判定对逐条调用本地模型共 431 次）远超规则模式，该耗时是 LLM 增强的真实成本，不改变规则模式主验收口径（规则模式 kilo 5.086s，见汇总报告）。

## 5. 瓶颈分析 Top3

1. parseCode: 1445.934s（39.6%，136 次逐方法 LLM 逻辑描述）
2. generateFormalSpecs: 790.729s（21.7%，29 次规约 LLM 生成 + Alloy 校验）
3. parseRequirements: 666.262s（18.3%，29 次需求 LLM 语义增强）

## 6. 优化建议

最慢阶段: parseCode（逐方法 LLM 逻辑描述）

- 生产建议采用 GAP-046「候选复核」架构：LLM 仅复核规则判定的候选缺陷对（万行保守上限 200 对），不逐方法全量增强——本次实测 runConsistencyCheck 二审 ≈2.1s/对，200 对预算约 7min
- 需求语义增强 / 规约生成可改为异步批量 + 缓存，避免阻塞全链路


---
**报告生成**: GAP-008 PerformanceTest（LLM enhanced mode single-run）
