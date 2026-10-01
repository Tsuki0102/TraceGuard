# cpp-loglib（C++ 演示样例工程）

T11 多语言扩展（2026-09-21）引入的 **C/C++** 演示工程：一个工程同时覆盖 **C++ 类方法**
（logger.cpp / logger.h，tree-sitter-cpp 语法）与 **C 风格自由函数**（util.c，同语法包兼容解析）。
2 个源文件 + 1 个头文件、10 条需求、6 处注入缺陷（defects.json 为权威清单）。

## 结构

```
cpp-loglib/
├── requirements.txt   # 需求规格（REQ-001~REQ-010）
├── code/
│   ├── logger.h       # Logger 类声明（无函数定义，验证头文件收集）
│   ├── logger.cpp     # Logger 类方法（open/write/flush/query/count/close）+ C 风格自由函数
│   └── util.c         # 纯 C 工具函数（前缀初始化/级别校验/长度截断）
└── code.zip           # 代码打包（上传入口与 Java/Python 工程一致）
```

## 注入缺陷

| ID | 位置 | 类型 | 检测来源 |
|---|---|---|---|
| D01 | util.c `init_prefix` | malloc 内存泄漏 | 基础缺陷信号（CFamilyCodeParser） |
| D02 | util.c `init_prefix` | strcpy 危险函数（CWE-120） | 基础缺陷信号（CFamilyCodeParser） |
| D03 | logger.cpp `open_file` | fopen 句柄未释放 | 基础缺陷信号（CFamilyCodeParser） |
| D04 | logger.cpp `write_line` | malloc 未判空 | 基础缺陷信号（CFamilyCodeParser） |
| D05 | logger.cpp `flush` | 裸 catch(...) 吞异常 | 基础缺陷信号（CFamilyCodeParser） |
| D06 | logger.cpp `maybe_flush` | 落盘阈值条件反转 | 需求-代码一致性判定（业务逻辑不一致） |

## 使用方式

1. 创建项目，技术栈填 `C++`（自动归一化为 cpp；填 `C` 则路由到纯 C 解析器，仅收 .c/.h）；
2. 上传 `requirements.txt` 与 `code.zip`；
3. 创建并运行分析任务（规则引擎即可，无需 LLM）；
4. 查看一致性结果 / 缺陷列表 / 追溯矩阵 / 报告导出，全流程与 Java/Python 工程一致。

> 单元测试：`backend/src/test/java/com/traceguard/spi/CFamilyCodeParserTest.java`
> （`mvn test -Dtest=CFamilyCodeParserTest`）
