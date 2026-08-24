# 统一通知服务 API（api-service）

## 项目概述

本项目是一个**统一通知服务 API** 的示例工程，模拟了一个支持多渠道（短信/邮件/推送）通知发送的后端服务。项目采用纯 JDK 标准库实现，不依赖任何第三方框架或库，旨在展示典型的 Controller-Service 分层架构。

## 场景描述

在实际业务中，系统需要向用户发送各类通知（验证码、订单状态、营销信息等），不同通知可能通过不同渠道送达。本服务提供统一的通知发送入口，封装了参数校验、幂等去重、限流控制、黑名单过滤、模板渲染、重试机制等通用能力，使上层业务只需关注通知内容本身。

## 技术栈

- **语言**: Java（JDK 8+）
- **依赖**: 仅使用 JDK 标准库（java.util, java.time, java.util.logging 等）
- **架构**: Controller-Service 分层，内存存储模拟
- **存储**: ConcurrentHashMap 模拟数据库（无持久化）

## 项目结构

```
api-service/
├── requirements.txt          # 需求文档（REQ-001 ~ REQ-010）
├── defects.json              # 注入缺陷清单（10 个缺陷）
├── README.md                 # 本文件
└── code/
    ├── Notification.java           # 通知实体模型
    ├── NotificationController.java  # API 控制器层
    ├── NotificationService.java     # 核心业务逻辑层
    ├── TemplateManager.java         # 模板管理器
    └── BlacklistManager.java        # 黑名单管理器
```

## 核心类说明

### Notification.java（通知实体）
通知的数据模型，包含以下字段：
- `id` - 通知唯一标识
- `userId` - 目标用户 ID
- `channel` - 发送渠道（SMS/EMAIL/PUSH）
- `content` - 通知内容
- `status` - 状态（PENDING/SENDING/SUCCESS/FAILED/CANCELLED）
- `createTime` - 创建时间
- `retryCount` - 重试次数
- `templateId` - 关联模板 ID
- `batchTaskId` - 批量任务 ID
- `deliveryStatus` - 投递回调状态

**方法数**: 约 15 个（getter/setter + 业务辅助方法 canRetry, incrementRetry, buildIdempotentKey）

### NotificationController.java（API 控制器层）
对外暴露的 API 入口，负责请求接收、参数组装和响应封装。

**方法清单**（10 个）:
1. `send()` - 发送单条通知
2. `batchSend()` - 批量发送通知
3. `queryHistory()` - 查询发送历史（分页）
4. `getDetail()` - 获取通知详情
5. `cancelSend()` - 取消待发送通知
6. `retrySend()` - 手动重试失败通知
7. `getStats()` - 获取统计信息
8. `healthCheck()` - 健康检查
9. `handleCallback()` - 处理投递状态回调
10. `autoReply()` - 自动回复（超范围实现）

### NotificationService.java（核心业务层）
通知服务的核心业务逻辑，包含发送流程编排和各项基础能力。

**方法清单**（约 18 个）:
1. `doSend()` - 执行完整发送流程
2. `validateParams()` - 参数校验
3. `checkIdempotent()` - 幂等去重检查
4. `checkRateLimit()` - 限流检查
5. `checkBlacklist()` - 黑名单检查
6. `selectChannel()` - 渠道选择
7. `formatContent()` - 内容格式化
8. `saveHistory()` - 保存发送记录
9. `updateDeliveryStatus()` - 更新投递状态
10. `retryWithBackoff(String)` - 指数退避重试
11. `retryWithBackoff(Notification)` - 重载重试方法
12. `batchProcess()` - 批量处理
13. `getBatchProgress()` - 查询批量进度
14. `getSuccessRate()` - 计算发送成功率
15. `getChannelDistribution()` - 渠道分布统计
16. `getById()` - 按 ID 查询通知
17. `queryHistory()` - 历史查询
18. `cancelNotification()` - 取消通知
19. `cleanIdempotentCache()` - 清理幂等缓存
20. `cleanRateLimitStore()` - 清理限流记录

### TemplateManager.java（模板管理器）
通知模板的增删改查及渲染功能。

**方法清单**（8 个）:
1. `create()` - 创建模板
2. `update()` - 更新模板
3. `delete()` - 删除模板（逻辑删除）
4. `getById()` - 按 ID 获取模板
5. `listAll()` - 列出所有模板
6. `render()` - 渲染模板（变量替换）
7. `validate()` - 校验模板参数
8. `cloneTemplate()` - 克隆模板

### BlacklistManager.java（黑名单管理器）
用户黑名单的管理功能。

**方法清单**（6 个）:
1. `add()` - 添加单个用户到黑名单
2. `remove()` - 从黑名单移除用户
3. `contains()` - 检查用户是否在黑名单中
4. `listAll()` - 列出所有黑名单用户
5. `batchAdd()` - 批量添加用户到黑名单
6. `exportList()` - 导出黑名单列表

## 方法统计

| 类名 | 方法数 |
|------|--------|
| Notification.java | ~15 |
| NotificationController.java | 10 |
| NotificationService.java | ~18 |
| TemplateManager.java | 8 |
| BlacklistManager.java | 6 |
| **合计** | **~57** |

## 代码行数

项目总代码行数约 **700+ 行**（不含空行和注释约 500 行有效代码）。

## 需求覆盖

| 需求 ID | 需求描述 | 对应实现 |
|---------|---------|---------|
| REQ-001 | 通知发送 + 参数校验 | NotificationController.send(), NotificationService.doSend() |
| REQ-002 | 幂等去重（60s 窗口） | NotificationService.checkIdempotent() |
| REQ-003 | 用户限流（10条/分钟） | NotificationService.checkRateLimit() |
| REQ-004 | 模板管理 CRUD | TemplateManager 全部方法 |
| REQ-005 | 发送历史分页查询 | NotificationController.queryHistory() |
| REQ-006 | 批量发送 + 进度跟踪 | NotificationController.batchSend(), NotificationService.batchProcess() |
| REQ-007 | 投递回调处理 | NotificationController.handleCallback() |
| REQ-008 | 黑名单管理 | BlacklistManager 全部方法 |
| REQ-009 | 重试 + 指数退避 | NotificationService.retryWithBackoff() |
| REQ-010 | 统计（成功率/渠道分布） | NotificationService.getSuccessRate(), getChannelDistribution() |

## 缺陷分布

项目共注入 **10 个缺陷**，分布如下：

| 缺陷类别 | 数量 | 缺陷 ID |
|---------|------|---------|
| 需求缺失 | 1 | DEFECT-001 |
| 代码超范围实现 | 1 | DEFECT-002 |
| 业务逻辑不一致 | 2 | DEFECT-003, DEFECT-004 |
| 约束条件不满足 | 2 | DEFECT-005, DEFECT-006 |
| 基础代码缺陷 | 4 | DEFECT-007, DEFECT-008, DEFECT-009, DEFECT-010 |

### 缺陷详情概要

1. **DEFECT-001**（需求缺失）: 代码中实现了审计日志功能，但需求文档中未定义此需求
2. **DEFECT-002**（超范围实现）: autoReply() 自动回复功能完全不在需求范围内
3. **DEFECT-003**（逻辑不一致）: 限流阈值设为 20 条/分钟，需求要求 10 条/分钟
4. **DEFECT-004**（逻辑不一致）: 幂等窗口设为 30 秒，需求要求 60 秒
5. **DEFECT-005**（约束不满足）: 参数校验只检查了 userId，遗漏了 channel 和 content
6. **DEFECT-006**（约束不满足）: 模板长度限制设为 1000 字符，需求要求 500 字符
7. **DEFECT-007**（代码缺陷）: retryWithBackoff() 中存在空 catch 块
8. **DEFECT-008**（代码缺陷）: batchAdd() 中存在空 catch 块
9. **DEFECT-009**（代码缺陷）: 模板渲染未对变量值转义，存在注入风险
10. **DEFECT-010**（代码缺陷）: 渠道分布统计存在潜在 NPE 风险

## 编译与运行

```bash
# 编译
javac -d out code/*.java

# 运行（需要自行编写 main 方法进行测试）
java -cp out com.sample.apiservice.NotificationController
```

## 注意事项

- 本项目为示例工程，使用内存存储，重启后数据丢失
- 未引入任何第三方依赖，所有功能均使用 JDK 标准库实现
- 代码中故意注入了多种类型的缺陷，用于代码审查和缺陷检测的评测
- 代码中的中文注释用于模拟真实业务项目的编码风格
