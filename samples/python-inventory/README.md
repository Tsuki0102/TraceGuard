# python-inventory（Python 演示样例工程）

T11 多语言扩展（2026-09-21）引入的 **Python** 演示工程，与 `ecommerce-order`（Java）口径一致：
2 个源文件约 130 行、10 条需求、6 处注入缺陷（defects.json 为权威清单）。

## 结构

```
python-inventory/
├── requirements.txt   # 需求规格（REQ-001~REQ-010）
├── code/
│   ├── store.py       # 数据访问层：建表/入库/查询/日志/统计
│   └── service.py     # 业务服务层：入库/出库/预警/删除/重试/配置
└── code.zip           # 代码打包（上传入口与 Java 工程一致）
```

## 注入缺陷

| ID | 位置 | 类型 | 检测来源 |
|---|---|---|---|
| D01 | store.py `add_stock_record` | 可变默认参数 | 基础缺陷信号（PythonCodeParser） |
| D02 | store.py `add_stock_record` | SQL注入拼接（f-string） | 基础缺陷信号（PythonCodeParser） |
| D03 | store.py `save_log` | 资源未释放（open 无 with） | 基础缺陷信号（PythonCodeParser） |
| D04 | service.py `outbound` | 库存校验条件反转 | 需求-代码一致性判定（业务逻辑不一致） |
| D05 | service.py `flush_cache_with_retry` | while True 无退出 | 基础缺陷信号（PythonCodeParser） |
| D06 | service.py `load_threshold_config` | 裸 except 吞异常 | 基础缺陷信号（PythonCodeParser） |

## 使用方式

1. 创建项目，技术栈填 `Python`（按 techStack 自动路由到 Python 解析器，无需改全局配置）；
2. 上传 `requirements.txt` 与 `code.zip`；
3. 创建并运行分析任务（规则引擎即可，无需 LLM）；
4. 查看一致性结果 / 缺陷列表 / 追溯矩阵 / 报告导出，全流程与 Java 工程一致。

> 单元测试：`backend/src/test/java/com/traceguard/spi/PythonCodeParserTest.java`
> （`mvn test -Dtest=PythonCodeParserTest`）
