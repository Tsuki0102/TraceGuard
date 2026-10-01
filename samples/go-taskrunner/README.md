# go-taskrunner（Go 演示样例工程）

T11 多语言扩展（2026-09-21）引入的 **Go** 演示工程，与前序 Java/Python/C++ 工程口径一致：
2 个源文件、10 条需求、6 处注入缺陷（defects.json 为权威清单）。
Go 特色基础缺陷信号是本工程的核心演示点（错误处理惯例检查为全系统独有）。

## 结构

```
go-taskrunner/
├── requirements.txt   # 需求规格（REQ-001~REQ-010）
├── code/
│   ├── runner.go      # TaskRunner 方法集（RunTask/LoadConfig/UpdateCounter/MaybeFlush/Cleanup）
│   └── util.go        # 包级函数（ParseSize/ValidLevel/LevelWeight/FormatSummary）
└── code.zip           # 代码打包（上传入口与 Java/Python/C++ 工程一致）
```

## 注入缺陷

| ID | 位置 | 类型 | 检测来源 |
|---|---|---|---|
| D01 | runner.go `RunTask` | err 接收后未检查 | 基础缺陷信号（GoCodeParser，Go 独有） |
| D02 | runner.go `LoadConfig` | Open 后无 defer Close | 基础缺陷信号（GoCodeParser） |
| D03 | runner.go `UpdateCounter` | Lock 后无 Unlock 死锁 | 基础缺陷信号（GoCodeParser） |
| D04 | runner.go `MaybeFlush` | 落盘阈值条件反转 | 需求-代码一致性判定（业务逻辑不一致） |
| D05 | runner.go `Cleanup` | error 被 _ 丢弃 | 基础缺陷信号（GoCodeParser，Go 独有） |
| D06 | util.go `ParseSize` | panic 滥用 | 基础缺陷信号（GoCodeParser） |

## 使用方式

1. 创建项目，技术栈填 `Go`（自动路由到 GoCodeParser）；
2. 上传 `requirements.txt` 与 `code.zip`；
3. 创建并运行分析任务（规则引擎即可，无需 LLM）；
4. 查看一致性结果 / 缺陷列表 / 追溯矩阵 / 报告导出，全流程与其他语言工程一致。

> 单元测试：`backend/src/test/java/com/traceguard/spi/GoCodeParserTest.java`
> （`mvn test -Dtest=GoCodeParserTest`）
