# TraceGuard - 软件需求-代码一致性验证与缺陷自动检测系统

基于形式化需求规约与大模型融合的软件需求-代码一致性验证与缺陷自动检测系统

## 项目概述

本系统面向中小软件企业敏捷开发场景与高校软件工程教学科研场景，聚焦软件开发全流程中「需求与代码不一致」的核心痛点，融合形式化需求规约的严谨性与大语言模型的语义理解能力，实现五大核心能力：

1. **自然语言需求自动化形式化建模** - 支持Word/PDF/Markdown格式需求文档自动解析，基于Kripke语义模型进行结构化提取，自动生成Alloy形式化规约
2. **Java代码静态解析与语义提取** - 基于JavaParser构建AST，提取代码方法级语义单元，生成逻辑描述，检测SQL注入、资源泄露、死循环等基础缺陷
3. **需求-代码多维度一致性校验** - 基于三维相似度公式（语义相似度+约束匹配度+不变量满足度）进行一致性判定
4. **缺陷自动定位与双向追溯** - 自动识别需求缺失、代码超范围、逻辑不一致等缺陷类型，生成需求-代码双向追溯矩阵
5. **可视化全流程管理平台** - 提供从项目创建、文档上传、分析执行到报告查看的全流程可视化界面

## 技术栈

### 后端
- **核心框架**: Spring Boot 2.7.18
- **JDK版本**: JDK 21（编译目标 java.version=21）
- **ORM框架**: MyBatis-Plus 3.5.3
- **代码分析**: JavaParser 3.25.5, Soot 4.7.0, Alloy 6.2.0
- **文档解析**: Apache POI 5.4.1 (Word), PDFBox 2.0.32 (PDF), CommonMark 0.21 (Markdown)
- **数据库**: MySQL 8.0
- **工具库**: Hutool 5.8.23, Knife4j (API文档)

### 前端
- **核心框架**: Vue 3 + Vite 4
- **UI组件库**: Element Plus 2.3
- **路由/状态**: Vue Router 4 + Pinia 2
- **图表库**: ECharts 5.4
- **HTTP客户端**: Axios

## 项目结构

```
TraceGuard/
├── backend/                    # 后端SpringBoot项目
│   ├── src/main/java/com/traceguard/
│   │   ├── TraceGuardApplication.java    # 启动类
│   │   ├── common/          # 通用响应类
│   │   ├── config/          # 配置类（CORS、MyBatis-Plus、异步任务等）
│   │   ├── controller/      # 控制器层
│   │   │   ├── AuthController.java       # 认证接口
│   │   │   ├── ProjectController.java    # 项目管理接口
│   │   │   ├── AnalysisController.java   # 分析任务接口
│   │   │   └── ResultController.java     # 结果查询接口
│   │   ├── core/            # 核心算法
│   │   │   └── ConsistencyChecker.java   # 一致性校验算法
│   │   ├── entity/          # 数据库实体类
│   │   ├── mapper/          # MyBatis Mapper接口
│   │   ├── service/         # 业务服务层
│   │   └── util/            # 工具类
│   │       ├── DocumentParserUtil.java   # 文档解析工具
│   │       ├── JavaCodeParserUtil.java   # Java代码解析工具
│   │       ├── RequirementAnalyzerUtil.java # 需求语义分析工具
│   │       └── FileStorageUtil.java      # 文件存储工具
│   └── src/main/resources/
│       ├── application.yml  # 主配置文件
│       ├── application-dev.yml # 开发环境配置
│       └── sql/init.sql     # 数据库初始化脚本
├── frontend/                   # 前端Vue3项目
│   ├── src/
│   │   ├── views/           # 页面组件
│   │   │   ├── Login.vue            # 登录页
│   │   │   ├── Layout.vue           # 主布局
│   │   │   ├── Dashboard.vue        # 工作台仪表盘
│   │   │   ├── Projects.vue         # 项目列表
│   │   │   ├── ProjectDetail.vue    # 项目详情/分析页面
│   │   │   ├── Results.vue          # 分析结果总览
│   │   │   ├── Requirements.vue     # 需求分析详情
│   │   │   ├── CodeView.vue         # 代码分析视图
│   │   │   ├── Defects.vue          # 缺陷报告
│   │   │   └── Traceability.vue     # 双向追溯矩阵
│   │   ├── api/             # API请求封装
│   │   ├── router/          # 路由配置
│   │   └── utils/request.js # Axios请求封装
│   └── package.json
├── docs/                       # 文档目录
│   ├── 01-需求与申报/          # 基准：软件需求规格说明书-V1.0.md、创新训练计划项目申报表.docx（不可修改）
│   ├── 02-设计与跟踪/          # 设计方案（整体设计方案.md）、技术白皮书、差距与改进编号台账
│   ├── 03-报告/               # 评测/性能/阈值标定/测试报告（手写文档，附实测数据）
│   └── 04-手册/               # 用户操作手册
└── scripts/                    # 运维脚本（smoke72h 冒烟等）
```

## 快速启动

### 环境要求
- JDK 21
- Maven 3.8+
- Node.js 20 LTS（与 CI / Dockerfile 口径一致）
- npm 或 yarn

### 一、启动后端服务

需先安装 MySQL 8.0 并创建数据库 `traceguard`（utf8mb4）：

```bash
# 进入后端目录
cd backend

# 使用Maven编译打包
mvn clean package -DskipTests

# 运行SpringBoot应用
mvn spring-boot:run
```

后端服务将在 `http://localhost:8080/api` 启动。

- API文档地址: http://localhost:8080/api/doc.html

**使用MySQL数据库**：
1. 检查 `application.yml` 中的数据库连接配置（host/port/用户名/密码）
2. 创建数据库 `traceguard`（字符集 utf8mb4）
3. 执行 `src/main/resources/sql/init.sql` 初始化表结构

### 二、启动前端服务

```bash
# 进入前端目录
cd frontend

# 安装依赖
npm install

# 启动开发服务器
npm run dev
```

前端服务将在 `http://localhost:3000` 启动。

### 三、访问系统

打开浏览器访问 `http://localhost:3000`，使用默认账号登录：
- **用户名**: admin
- **密码**: admin123

## Windows 一键部署（AUD-12）

Windows 10/11 原生环境（无需 Docker Desktop）可通过脚本一键部署，两种方式并存，Docker 方式不受影响：

### 使用 `deploy-windows.ps1`（推荐，自动完成初始化+构建+生成启动脚本）

1. **前置安装**（均需加入系统 Path）：JDK 21+、Maven 3.8+、MySQL 8.0+、Node.js 20 LTS+。
2. 打开 PowerShell，在项目根目录执行：
   ```powershell
   powershell -ExecutionPolicy Bypass -File scripts\deploy-windows.ps1 -MysqlUser root -MysqlPass '你的密码'
   ```
   脚本按序完成：前置检查 → 数据库初始化（`init.sql`，幂等，库已存在则跳过）→ 后端构建（`mvn -B package -DskipTests`）→ 前端构建（`npm ci && npm run build`）→ 生成启动脚本。
3. 启动：双击项目根目录生成的 `start-all.bat`（并行拉起前后端并打开浏览器），或分别双击 `start-backend.bat` / `start-frontend.bat`。
   - 后端：`http://localhost:8080/api`（prod 配置，`logs/traceguard.log`）
   - 前端：优先 nginx（`frontend\nginx-windows.conf`，端口 80）；未安装 nginx 时自动降级 `npx vite preview --port 3000`
4. 浏览器访问 `http://localhost:3000`（或 nginx 的 `http://localhost`），默认账号 `admin / admin123`（登录后请尽快修改）。

### 常用参数

| 参数 | 说明 | 默认值 |
|---|---|---|
| `-MysqlUser` | MySQL 用户名 | `root` |
| `-MysqlPass` | MySQL 密码（缺省时交互提示输入） | 空 |
| `-SkipBuild` | 跳过前后端构建（仅生成启动脚本） | 关闭 |

### 常见问题

- **JDK/MySQL/Node 提示未找到**：确认相应程序已加入系统 Path 后重新打开 PowerShell 再执行。
- **8080/3000 端口被占用**：停掉占用进程，或修改 `application-prod.yml`（`server.port`）与 `vite.config.js`（`server.port`）。
- **数据库连接失败**：确认 MySQL 服务已启动、密码正确；可通过 `MYSQL_USER`/`MYSQL_PASSWORD` 等环境变量覆盖 `application-prod.yml` 中的默认连接。
- **生产密钥加固（SEC-02/03）**：`prod` 配置下 `JWT_SECRET` / `STORAGE_ENCRYPT_KEY` / `BACKUP_ENCRYPT_KEY` / `LLM_CONFIG_KEY` 均已移除默认回退——**未注入即拒绝启动**（fail-fast）。请使用强随机值注入（如 `docker-compose` 或 shell 环境变量）；开发环境（dev）可沿用内置默认值但启动时会有 WARN 告警。
- **重复部署**：脚本幂等可重跑；已构建产物与已初始化数据库会自动跳过。

## 核心算法说明

### 一致性校验三维相似度公式

**Sim(Ri,Cj) = α·Cos(EmbR,EmbC) + β·Con(Ri,Cj) + γ·Inv(Ri,Cj)**

- **α (语义相似度)**: 默认权重0.4，基于分词后的Jaccard相似度+方法名匹配增强
- **β (约束匹配度)**: 默认权重0.35，检测空值校验、参数验证、异常处理、日志记录等约束实现
- **γ (不变量满足度)**: 默认权重0.25，检测返回值、语法正确性、访问修饰符等不变量

**判定阈值**（对齐 SRS FR-CHECK-002 与代码实际行为：Sim > T1 严格大于判「完全一致」）：
- T1 = 0.8 (默认): Sim > T1 → 完全一致
- T2 = 0.5 (默认): T2 <= Sim <= T1 → 一般不一致
- Sim < T2 → 严重不一致

### 缺陷类型分类

1. **需求缺失**: 需求条目在代码中未找到对应实现
2. **代码超范围实现**: 代码方法未对应到任何需求条目
3. **业务逻辑不一致**: 语义相似度较低，实现逻辑与需求描述偏差大
4. **约束条件不满足**: 需求中的约束条件（参数校验、空值检查等）未实现

### 支持的代码基础缺陷检测

- SQL注入风险检测
- 逻辑死循环检测 (while(true)无退出条件)
- 资源未释放检测 (未使用try-with-resources/finally关闭流)

## 使用流程

1. **登录系统** - 使用admin/admin123登录
2. **创建项目** - 在工作台或项目列表页创建新项目
3. **上传需求文档** - 支持.docx/.pdf/.md/.txt格式
4. **上传代码工程** - 将Java Maven/Gradle项目打包为ZIP上传
5. **配置参数** - 调整权重系数和判定阈值（使用默认值即可）
6. **启动分析** - 一键执行全流程自动化分析
7. **查看结果** - 查看统计图表、需求分析、代码分析、缺陷报告、追溯矩阵

## 模块说明

| 模块 | 功能 | 对应需求编号 |
|------|------|-------------|
| 需求预处理与形式化建模 | 文档导入解析、语义提取、Alloy规约生成、模型校验 | FR-REQ-001 ~ FR-REQ-004 |
| 代码语义提取与静态分析 | 项目导入、AST/CFG构建、语义表征、基础缺陷检测 | FR-CODE-001 ~ FR-CODE-004 |
| 一致性校验与缺陷定位 | 三维相似度计算、分级判定、缺陷定位、追溯矩阵、报告生成 | FR-CHECK-001 ~ FR-CHECK-005 |
| 可视化管理平台 | 用户管理、项目管理、任务管理、统计分析、数据管理 | FR-PLAT-001 ~ FR-PLAT-004 |

## 支持的浏览器（GAP-042 / SRS 5.5.2）

前端已显式声明并适配以下浏览器（详见 `frontend/.browserslistrc`、`frontend/vite.config.js` 的 `build.target` 与 `css.postcss`）：

| 浏览器 | 最低版本 |
|---|---|
| Google Chrome | 100 |
| Microsoft Edge | 100 |
| Mozilla Firefox | 100 |

构建产物已通过 `autoprefixer` 自动补全 CSS 前缀，并以 `chrome100,edge100,firefox100` 为目标编译，确保在上述版本上正常运行。低于上述版本的浏览器可能存在样式或语法兼容问题，建议升级后使用。

## 注意事项

1. 数据库统一使用 MySQL 8.0（开发/测试/生产一致），启动前先执行 `init.sql` 初始化。
2. **大模型（LLM）能力默认关闭**：需求解析与形式化建模的「LLM 智能增强」能力默认处于关闭状态（GAP-033、AUD-04）。系统使用基于规则的方法即可完成全部核心功能，无需任何外部大模型 API。如需启用 LLM（如语义增强、缺陷解释、修复建议），请由管理员在「系统设置 → LLM 配置」中开启 `enabled` 并填写 `baseUrl`/`apiKey`（详见本文「大模型（LLM）配置」一节与 `.env.example`）。
3. 代码控制流图(CFG)基于 Soot 4.7.0 真实字节码分析构建（含异常边、编译降级链与 Java 8/11/17 分版本适配），非简化实现（详见《技术白皮书》）。
4. 上传的文件存储在 `./uploads/` 目录下。

## 大模型（LLM）配置

系统的大模型能力属于**可选增强**，默认关闭，开箱即用无需任何 LLM 配置：

- **默认状态**：`enabled=false`。未配置时所有与大模型相关的功能（语义增强解释、缺陷智能解释、修复建议）自动降级为规则实现，不影响需求-代码一致性校验主流程。
- **如何开启**（管理员）：
  1. 进入「系统设置 → LLM 配置」；
  2. 打开「启用大模型」，选择 provider（如 `deepseek` / `openai` / `qwen`），填写 `baseUrl` 与 `apiKey`；
  3. 点击「保存」并「测试连通性」，状态变为 `enabled=true` 即生效。
- **演示配置（.env.example）**：仓库提供 `.env.example` 作为配置模板，复制为 `.env` 后填入真实 `apiKey` 即可，该文件不含任何真实密钥，仅用于说明变量名与格式。
- **配置项说明**：`LLM_ENABLED`（true/false）、`LLM_PROVIDER`、`LLM_BASE_URL`、`LLM_API_KEY`。后端读取 `llm.enabled` 等配置项，未配置时 `getStatus()` 返回 `enabled=false`。
- **安全提示**：`apiKey` 仅存储于服务端配置（前端 `getStatus`/`getConfig` 均做脱敏处理），请勿将真实密钥提交至版本库。

### 本地 CodeLlama + LangChain 真集成（GAP-043 / GAP-044）

系统已原生支持**本地开源 CodeLlama 推理**与 **LangChain（langchain4j）编排**，无需任何云端 API Key，满足申报书「基于开源 CodeLlama + LangChain 框架研发自动转换引擎」的承诺。Ollama/vLLM 均提供 OpenAI 兼容的 `/chat/completions` 端点，后端 `OpenAiLlmClient` 复用，CodeLlama 即走本地真实推理。

**1. 安装并拉起 Ollama（本地模型服务）**

```bash
# 下载安装 Ollama：https://ollama.com
# 拉取 CodeLlama（7b 约 3.8GB；显存不足可选 codellama:7b-instruct 或 13b）
ollama pull codellama:7b
ollama serve          # 默认 OpenAI 兼容端点 http://localhost:11434/v1
```

仓库已提供一键脚本 `scripts/pull-codellama.sh`（自动拉取 + 启动 + 自检）：

```bash
bash scripts/pull-codellama.sh          # 默认 7b
bash scripts/pull-codellama.sh 13b      # 指定规模
```

> **Windows 安装提示（实测）**：Ollama 图形安装向导默认不显示路径选择，可用命令行指定安装目录与模型目录：
> ```powershell
> # 1) 指定 Ollama 程序安装路径（先关闭已打开的安装窗口再用命令行运行安装程序）
> Start-Process "OllamaSetup.exe" -ArgumentList '/DIR="D:\develop\Ollama"' -Wait
> # 2) 指定模型存放路径（避免占用 C 盘；约 3.8GB/7b）
> [Environment]::SetEnvironmentVariable("OLLAMA_MODELS", "D:\develop\Ollama\models", "User")
> # 3) 重启 PowerShell 后拉取，模型即落到 D 盘
> ollama pull codellama:7b
> ```
> 安装器会自动把 `D:\develop\Ollama` 加入用户 PATH，重开终端即可直接使用 `ollama` 命令；`OLLAMA_MODELS` 设置后所有 `ollama pull` 都会下载到该目录。安装完成后 Ollama 默认后台自启并监听 `11434`，可用 `curl http://localhost:11434/api/tags` 验证。

**2. 配置 `application.yml`**

`traceguard.llm.providers` 已内置 `codellama`（指向本地 Ollama，可通过环境变量 `CODELLAMA_BASE_URL` 覆盖）：

```yaml
traceguard:
  llm:
    enabled: true
    providers:
      codellama:
        base-url: ${CODELLAMA_BASE_URL:http://localhost:11434/v1}
        api-key: ""        # Ollama 不校验密钥，留空即可
        timeout-seconds: 120
    # 引擎：self=OpenAI 兼容客户端（默认）；langchain=langchain4j 编排
    engine: ${LLM_ENGINE:self}
    routing:
      consistency-check: codellama     # 一致性判定环节切到本地 CodeLlama
    models:
      consistency-check: codellama:7b
```

> 注意：本地模型 `api-key` 为空也能启用——`LlmService.isEnabled()` 对 base-url 指向本机（localhost/127.0.0.1）的 provider 视为可用。

**3. 切换引擎（self / langchain）**

- `engine: self`（默认）：直接用 `OpenAiLlmClient` 调用本地 CodeLlama，零额外依赖。
- `engine: langchain`：**LangChain 真集成**，`LangChainAdapter` 以 langchain4j 的 `OpenAiChatModel` 编排「需求-代码一致性判定」链（System+User 双角色消息）。langchain4j 为 `optional` 依赖，缺失或下游异常时 `ConsistencyJudge` **自动回退 self 引擎**，主流程不中断。

生产环境通过环境变量切换（无需改 yml）：

```bash
LLM_ENGINE=langchain      # 启用 LangChain 编排（GAP-044）
CODELLAMA_BASE_URL=http://localhost:11434/v1
CODELLAMA_MODEL=codellama:7b
```

**4. 连通性测试**

- 管理端「系统设置 → LLM 配置 → 测试连通性」选择 `codellama` provider；
- 或调用后端接口 `POST /api/llm/test?provider=codellama`，返回模型回复即打通。

**5. 评测演示（端到端真集成）**

```bash
cd backend
# LangChain 编排本地 CodeLlama
LLM_PROVIDER=codellama LLM_ENGINE=langchain mvn -B test -Dtest=DefectDetectionEvalTest
# 仅 self 引擎（OpenAI 兼容客户端直连 CodeLlama）
LLM_PROVIDER=codellama LLM_ENGINE=self    mvn -B test -Dtest=DefectDetectionEvalTest
```

> **Windows PowerShell 等价写法**（用 `$env:` 前缀）：
> ```powershell
> cd D:\Develop\TraceGuard\backend
> $env:LLM_PROVIDER="codellama"; $env:LLM_ENGINE="langchain"
> mvn -B test -Dtest=DefectDetectionEvalTest
> # 或 self 引擎：
> $env:LLM_PROVIDER="codellama"; $env:LLM_ENGINE="self"
> mvn -B test -Dtest=DefectDetectionEvalTest
> ```

> 本仓库 CI 环境无本地 GPU/Ollama，真集成代码已落地并通过编译与默认引擎回归；**端到端 CodeLlama×LangChain 推理需在本机按上述步骤拉起 Ollama 后复测**。

## 扩展指南（多语言 / 多形式化语言，AUD-10）

系统解析层与规约层已抽象为可扩展 SPI 接口，支持后续接入 C#/Python 代码解析、TLA+ 等规约语言，无需改动主流程：

- **代码解析**：实现 `com.traceguard.spi.CodeParser`（`language()` 返回语言标识，如 `csharp`/`python`），注册为 Spring `@Component`。
- **规约生成**：实现 `com.traceguard.spi.SpecGenerator`（`specLanguage()` 返回如 `tlaplus`）。
- **规约校验**：实现 `com.traceguard.spi.SpecVerifier`。
- **注册与切换**：Spring 自动将全部实现注入 `ParserRegistry` / `SpecRegistry`（按 `language()` 建索引）。在 `application.yml` 设置 `traceguard.analysis.code-language` / `traceguard.analysis.spec-language` 切换语言（默认 `java` / `alloy`，行为与原链路完全一致）。
- **未匹配处理**：配置的语言无对应实现时，AnalysisService 抛出 `BusinessException` 并列出可用语言，便于快速定位。
- **示例骨架**（C# 解析器）：
  ```java
  @Component
  public class CSharpCodeParserAdapter implements CodeParser {
      public String language() { return "csharp"; }
      public JavaCodeParserUtil.ProjectParseResult parseProject(String path, SootCfgBuilderUtil.CompileResult cr) {
          // 调用 C# 解析库，结果映射为 CodeUnit 列表与失败收集
          return new JavaCodeParserUtil.ProjectParseResult();
      }
  }
  ```
  TLA+ 规约校验器同理实现 `SpecGenerator` + `SpecVerifier`，并将 `spec-language` 改为 `tlaplus` 即生效。

## 许可

本项目为大学生创新创业训练计划项目，郑州轻工业大学。
