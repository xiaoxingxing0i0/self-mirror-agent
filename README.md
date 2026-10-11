# self-mirror-agent（自我镜像智能体 / 人格回溯 Agent）

面向 C 端个人私有智能体，基于 **DP 动态规划调度器** 的多 Agent 架构。

> **硬性约束**
> 1. 禁止做临床心理诊断；
> 2. 所有结论必须能够溯源到用户原始私有数据。

## 技术栈

| 组件 | 选型 |
|------|------|
| 语言 / 框架 | Java 21、Spring Boot 3.4.x |
| 大模型接入 | Spring AI 1.0（OpenAI 兼容协议，默认通义千问） |
| 关系存储 | MySQL 8.0 + MyBatis-Plus（任务、用户、对话原文） |
| 向量存储 | Milvus 2.4（对话向量、RAG 知识库向量） |
| 缓存 | Redis 7.x（会话上下文、任务状态、Agent 临时消息） |
| 调度 | Quartz（周期任务）、Resilience4j（重试 / 熔断 / 降级） |
| 迁移 | Flyway |
| 构建 | Maven |

## Agent 一览

| Agent | 包路径 | 职责 |
|-------|--------|------|
| DP 动态规划调度 Agent | `dp/scheduler` | 全局中枢：请求分发、链路编排、状态同步、会话管理 |
| 语言分析 Agent | `agent/resident` | 情绪 / 意图 / 心理 / 任务多维打标，结构化标签输出 |
| 用户对话 Agent | `agent/resident` | 共情回复生成、任务通知、SSE 流式输出 |
| 检索 Agent | `agent/resident` | 历史对话检索 + RAG 知识库检索（强制 user_id 隔离） |
| 任务执行 Agent | `agent/resident` | 任务创建 / 拆解 / 生命周期管理 / 定时触发 |
| 合规检查 Agent | `agent/resident` | 两级校验：本地敏感词 + 大模型语义合规（禁止诊断） |
| 用户画像与报告 Agent | `agent/resident` | 画像聚合更新、多维度分析报告生成 |

## 目录结构

```
src/main/java/com/mirror/agent
├── config          // Spring-AI / Milvus / Redis / Quartz 配置 Bean
├── common          // 统一响应、错误码（docs/error.md）、业务异常
├── dp
│   ├── entity      // TaskState、AgentExecutionPlan、SubTask（运行时）
│   └── scheduler   // DpScheduleAgent 调度核心、会话上下文管理
├── agent
│   ├── resident    // 常驻 Agent 实现
│   └── temp        // 动态临时 Agent
├── message         // Agent 通信消息 POJO
├── tool            // 工具层：敏感词过滤、文件生成、ApiClient 抽象
├── repository      // MyBatis-Plus Mapper + Milvus 向量库封装
├── entity          // 数据库实体（docs/data_model.md）
├── service         // 上层业务服务
├── controller      // REST 接口 + 全局异常处理
└── util            // Markdown 报告渲染、JSON、用户上下文等
```

## 快速开始

### 1. 启动基础设施

```bash
docker compose -f docker/docker-compose.yml up -d
# mysql:3306  redis:6379  milvus:19530
```

### 2. 配置大模型密钥

默认对接阿里云百炼（通义千问）OpenAI 兼容模式：

```bash
export AI_API_KEY=sk-xxxx
# 可选：切换任意 OpenAI 兼容服务
# export AI_BASE_URL=https://api.deepseek.com
# export AI_CHAT_MODEL=deepseek-chat
```

### 3. 启动应用

```bash
mvn spring-boot:run
```

Flyway 会自动建表（9 张）并写入 RAG 知识库种子数据。

### 4. 验证

```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"demo","password":"demo123456","nickname":"Demo"}'

TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"demo","password":"demo123456"}' | grep -o '"data":"[^"]*' | cut -d'"' -f4)

curl -X POST "http://localhost:8080/api/chat?content=最近工作压力好大，有点焦虑" \
  -H "Authorization: Bearer $TOKEN"
```

## 核心链路（docs/scene.md）

```
用户输入 → DP 调度 → 合规(输入) → 语言分析(打标) → 路由分支
  ├─ 闲聊/情绪 → 对话 Agent → 合规(输出) → 回复 + 异步归档(消息/标签/向量)
  ├─ 建议咨询 → 检索 Agent(RAG) → 对话 Agent → 合规(输出) → 回复
  └─ 任务指令 → 任务 Agent(建任务/拆子任务) → 后台执行 → 通知用户
```

## 硬性规则（CI 校验，见 .claude/CLAUDE.md）

1. 依赖方向：`domain → config → mapper → service → controller`（内层不依赖外层）
2. 横切关注点（auth/log/telemetry）只能通过 Spring 注入，禁止 `new` 实例化
3. 单文件 ≤ 300 行；单方法 ≤ 50 行
4. 禁止 `System.out.println` / `e.printStackTrace()`，统一使用 SLF4J
5. 禁止裸 `RestTemplate` / `HttpURLConnection`，统一通过 `ApiClient` 抽象
6. 禁止字段级 `@Autowired`，必须构造器注入（Lombok `@RequiredArgsConstructor`）

## 详细规范

| 主题 | 路径 |
|------|------|
| 系统架构及业务流程 | docs/system_architecture.md |
| Agent 定义 | docs/agent_definition.md |
| Agent 实现方案 | docs/agent_implementation.md |
| 数据模型定义 | docs/data_model.md |
| 典型业务场景定义 | docs/scene.md |
| 错误码定义 | docs/error.md |
