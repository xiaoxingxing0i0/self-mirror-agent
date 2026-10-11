# 自我镜像智能体 · Vue 3 Web 前端代码生成提示词

> **本文档的用法**：把本文档交给 Claude Code，按 §10 的分阶段指令逐段执行。
> 每一阶段是一条独立提示词，**不要一次让它生成全部代码**（会触发上下文截断与契约走样）。
>
> **本文档的权威性**：§3、§4 的接口契约与数据字典是从后端源码逐字段抄录的，是唯一事实来源。
> 与之冲突的一切惯例（例如"REST 应该用 PATCH 删除""SSE 用 EventSource"）一律以本文档为准。
> 若发现文档与后端代码不符，**先改文档、再写代码**，并在回复中标注差异。

---

## 1. 角色与目标

你是一名资深 Vue 3 前端工程师，为一个已经上线的 Java 后端补齐 C 端 Web 客户端。

**产品**：自我镜像智能体（人格回溯 Agent）—— 面向个人的私有对话智能体。用户与 Agent 日常对话，
系统实时提取情绪/意图/心理标签并归档，之后可回溯历史对话、生成情绪分析报告。

**本项目的两条产品硬性约束**（继承自后端 `.claude/CLAUDE.md`，前端必须体现在 UI 上）：

1. **禁止临床心理诊断表述**。前端不得出现"抑郁症""焦虑症""确诊""治疗方案"等字样；
   标签一律呈现为"倾向/特征"，情绪标签呈现为"情绪记录"。免责声明需在画像页与报告页底部常驻展示。
2. **所有结论必须可溯源到用户原始私有数据**。任何统计数字、标签、报告结论，UI 上都必须提供
   回到原始对话消息的入口（见 §5.7 溯源抽屉）。不可溯源的结论不展示。

**代码质量标准**：可直接 `pnpm build` 部署，无 `TODO`、无假数据残留（mock 走 §8.4 的开关，不硬编码在组件里）。

---

## 2. 技术栈与工程约束

### 2.1 固定选型

| 用途 | 选型 | 备注 |
|---|---|---|
| 框架 | Vue 3 + `<script setup lang="ts">` | **Composition API 强制**，禁止 Options API |
| 构建 | Vite + TypeScript（`strict: true`） | 禁止 `any` 兜底、禁止 `@ts-ignore`（必要时 `@ts-expect-error` + 说明） |
| UI 组件库 | Element Plus（全量引入即可） | 组件前缀 `el-*`；自动导入用 `unplugin-vue-components`，见 §2.3 |
| 状态 | Pinia | 仅放跨页面共享状态；页面局部状态用 `ref/reactive` |
| 路由 | Vue Router 4 | history 模式 |
| HTTP | axios（仅用于普通 JSON 接口） | SSE 必须用 `fetch`，见 R4 |
| 流式 | 原生 `fetch` + `ReadableStream`，自写 SSE 解析器 | 见 §6.3 |
| 图表 | ECharts + vue-echarts | 只引入用到的图表（`echarts/core` 按需注册） |
| Markdown | `markdown-it`（GFM 表格）+ `dompurify` | 见 R12，禁止裸 `v-html` |
| 日期 | `dayjs`（含 `customParseFormat` 插件） | 见 R8 |
| 大数 JSON | `json-bigint`（`storeAsString: true`） | 见 R1，不可省略 |
| 工具 | `@vueuse/core` | 用 `useLocalStorage`/`useIntervalFn`/`onClickOutside`，别手写重复逻辑 |
| 测试 | Vitest + @vue/test-utils | 覆盖 §8.2 列出的 5 个关键单测 |
| Lint | ESLint（`eslint-plugin-vue` flat config）+ Prettier | — |

**版本号纪律**：执行安装命令前用 `npm view <pkg> version` 确认当前稳定主版本，**不要凭记忆写版本号**。
若 Pinia/Vite 主版本与本文档示例不同，按官方当前 API 写，并在回复中说明。

**禁止引入**：Tailwind、Naive UI、Ant Design Vue、Lodash（用 ES 原生）、moment、socket.io 等 WebSocket 库（后端无 WS）、axios 之上的第三方请求封装库（HTTP 层必须自写，见 §6.1）。

### 2.2 目录结构（严格照此落地）

```
D:\project\self-mirror-agent-web
├── index.html
├── vite.config.ts
├── tsconfig.json / tsconfig.app.json / tsconfig.node.json
├── .env.development            # VITE_API_BASE=/api  VITE_USE_MOCK=0
├── .env.production
├── package.json
└── src
    ├── main.ts
    ├── App.vue
    ├── api
    │   ├── http.ts             # axios 实例 + Result 拆包 + 错误映射（§6.1）
    │   ├── sse.ts              # streamChat（§6.3）
    │   ├── auth.ts / chat.ts / conversation.ts / report.ts / task.ts / knowledge.ts
    ├── types
    │   ├── api.d.ts            # 后端实体 1:1 的 TS 类型（§3.4）
    │   ├── common.ts           # Id / Result / BizError
    ├── constants
    │   ├── dict.ts             # 状态/类型/标签字典（§4）
    │   └── palette.ts          # 情绪色板（§7.3）
    ├── stores
    │   ├── auth.ts  conversation.ts  chat.ts
    ├── composables
    │   ├── useChatStream.ts    # 发送/流式/中断/重试
    │   ├── useTraceability.ts  # 溯源抽屉数据装载
    │   ├── usePolling.ts       # 任务进度轮询
    │   └── useMarkdown.ts      # markdown-it + DOMPurify
    ├── router/index.ts         # 路由 + 守卫
    ├── layouts/AppLayout.vue   # 侧栏 + 顶栏（桌面）/ 抽屉（移动）
    ├── views
    │   ├── LoginView.vue  RegisterView.vue
    │   ├── ChatView.vue
    │   ├── ReportListView.vue  ReportDetailView.vue
    │   ├── TaskListView.vue
    │   ├── KnowledgeView.vue
    │   └── ProfileView.vue
    ├── components
    │   ├── chat/  (MessageBubble, TagChips, TraceDrawer, Composer, StreamGuard)
    │   ├── report/ (StatCards, TagPieChart, EmotionTrendChart, MarkdownViewer)
    │   ├── task/ (TaskRow, TaskProgress)
    │   └── common/ (EmptyState, ErrorState, BizErrorBanner, TimeText)
    ├── mocks/                  # §8.4 mock adapter + fixtures（契约与真实一致）
    └── styles/ (tokens.css element-overrides.css base.css)
```

### 2.3 硬性工程规则（与后端 CI 规则同源）

1. **单文件（.vue/.ts）≤ 300 行**。超限必须拆子组件或抽 composable——这条会被检查，不要讨价还价。
2. 禁止 `console.log` 调试残留；日志走 `src/utils/logger.ts`（封装 `import.meta.env.DEV` 判断）。
3. 禁止在组件里直接写 `axios.get('/api/xxx')`；**所有请求必须经 `src/api/*` 的函数**。
4. 禁止在组件里写魔法字符串状态码（`'200'`、`status === 2`）；一律从 `constants/dict.ts` 引枚举。
5. 禁止 `provide/inject` 跨多层传业务数据；禁止全局事件总线。
6. 每个视图必须实现 **loading / empty / error 三态**，缺一个都算未完成。
7. 组件名 PascalCase 多词；composable `use` 前缀；类型 `type` 优先于 `interface`（需 extends 时用 interface）。
8. `v-for` 必须 `:key="item.id"`，禁止用 index（列表会因后端按时间倒序重排）。

---

## 3. 后端接口契约（唯一事实来源）

### 3.1 通用约定

| 项 | 值 |
|---|---|
| 后端基址 | `http://localhost:8080`，无 context-path |
| 前缀 | 所有业务接口以 `/api` 开头 |
| 编码 | UTF-8；请求体 `application/json` |
| 鉴权 | 除 `/api/auth/**`、`/actuator/**` 外，所有 `/api/**` 需要请求头 `Authorization: Bearer <token>` |
| Token 生命周期 | Redis 存储，**24 小时过期**，无刷新接口（过期即 300001 → 跳登录） |
| 追踪 | 请求可带 `X-Trace-Id`，响应头 `X-Trace-Id` 与响应体 `traceId` 均回传 |
| 日期 | `LocalDateTime` 序列化为 `yyyy-MM-dd HH:mm:ss`（时区 Asia/Shanghai）；`LocalDate` 为 `yyyy-MM-dd` |
| 空字段 | Jackson `default-property-inclusion: non_null` —— **值为 null 的字段整个键不存在** |

### 3.2 统一响应结构 `Result<T>`

```jsonc
{ "code": "000000", "message": "成功", "data": { }, "traceId": "a1b2c3..." }
```

`code` 是 **6 位字符串**（不是数字）。`"000000"` 为成功。HTTP 状态与业务码**解耦**，见 R10。

### 3.3 接口清单（后端 Controller 全量，共 17 个）

| # | 方法 | 路径 | 入参 | 返回 `data` | 说明 |
|---|---|---|---|---|---|
| 1 | POST | `/api/auth/register` | `{username, password, nickname?}` | `Id`（新用户 ID） | username 3–32，password **8–64**，nickname ≤32 |
| 2 | POST | `/api/auth/login` | `{username, password}` | `{token, userId, nickname}` | ⚠️ `nickname` 实际回显的是**用户名**（后端取 `request.getUsername()`），不要当成昵称展示 |
| 3 | POST | `/api/auth/logout` | 无体，需 `Authorization` 头 | `null` | 头缺失会导致后端 500，前端必须始终带上 |
| 4 | POST | `/api/chat` | `{content, conversationId?}` | `ChatResponse` | 同步整段回复；content 必填、**≤4000 字**；conversationId 缺省时后端新建会话 |
| 5 | GET | `/api/chat/stream` | query：`content`（必填）、`conversationId`（选填） | `text/event-stream`，裸文本分片 | SSE；**无 event 名、无 `[DONE]` 哨兵**，连接关闭即结束；见 R4/R5/R6 |
| 6 | GET | `/api/conversations` | 无 | `Conversation[]` | 按 `lastActiveTime` 倒序 |
| 7 | GET | `/api/conversations/{id}/messages` | — | `Message[]` | 按时间**正序**，全量返回无分页 |
| 8 | POST | `/api/conversations/{id}/close` | — | `null` | 置 `status=1`；已结束的会话再发消息后端可能报 201001 |
| 9 | GET | `/api/reports` | 无 | `AnalysisReport[]` | 按 `createTime` 倒序，无分页 |
| 10 | GET | `/api/reports/{id}` | — | `AnalysisReport` | 非本人/不存在 → `407001` |
| 11 | GET | `/api/tasks` | 无 | `Task[]` | **不含子任务步骤**（见 §9 B2） |
| 12 | POST | `/api/tasks/{id}/cancel` | — | `null` | 非法状态流转 → `205001` |
| 13 | POST | `/api/tasks/{id}/retry` | — | `null` | 重试超限 → `205003` |
| 14 | GET | `/api/knowledge` | 无 | `KnowledgeDoc[]` | 全局共享知识库，**非用户私有数据** |
| 15 | POST | `/api/knowledge` | `{title, category, content}` | `Id` | 三者均必填非空 |
| 16 | POST | `/api/knowledge/vectorize` | 无 | `Integer`（本次向量化条数） | 处理所有 `status=0` 文档，耗时可达数十秒 → 需按钮 loading + 超时放宽 |
| 17 | — | `/actuator/health` | 无 | — | 免鉴权，可用于启动自检 |

**任务创建没有独立接口**：任务由对话链路触发（用户在对话里说"生成我的周报"→ 后端识别 `task` 意图自动建任务）。
前端**不要**做"新建任务"表单，只提供任务列表的观察与取消/重试。这是产品设定，不是遗漏。

### 3.4 TS 类型定义（`src/types/api.d.ts`，逐字段对齐实体）

```ts
import type { Id } from './common'

/** 标签项：ChatResponse.tags 元素（后端 message/TagItem） */
export interface TagItem {
  category: 'emotion' | 'intent' | 'psychology' | 'task'
  code: string
  name: string
  confidence: number          // 0–1，两位小数
  needConfirm: boolean        // true = 置信度 < 0.7 的待确认标签
}

/** POST /api/chat 响应（后端 ChatResponse，@Builder + non_null） */
export interface ChatResponse {
  conversationId: Id
  userMessageId?: Id          // intercepted 分支不存在
  replyMessageId?: Id         // 同上
  reply: string
  replyType: ReplyType
  emotionTone?: string        // 无情绪标签时为「平静」
  tags?: TagItem[]            // intercepted 分支不存在
  taskId?: Id                 // 仅 task_notify 分支存在
  needClarify: boolean
}

export type ReplyType = 'chat' | 'consult' | 'query' | 'task_notify' | 'clarify' | 'intercepted'

export interface Conversation {
  id: Id; userId: Id; title?: string   // 首条用户消息前为 null
  startTime?: string; lastActiveTime?: string
  status: 0 | 1                        // 0 进行中 1 已结束
  messageCount: number
  createTime: string; updateTime: string
}

export interface Message {
  id: Id; conversationId: Id; userId: Id
  role: 0 | 1                          // 0 用户 1 系统
  content: string
  vectorId?: string                    // 向量归档完成前为空 → 非空才可溯源检索
  createTime: string
  isDeleted?: 0 | 1
}

export interface Task {
  id: Id; userId: Id
  taskType: 'report' | 'statistic' | 'profile_update'
  taskName: string
  taskParams?: string                  // ⚠️ JSON 字符串，非对象（R9）
  status: 0 | 1 | 2 | 3 | 4            // 0待执行 1执行中 2已完成 3失败 4已取消
  cronExpression?: string
  progress?: number                    // 0–100
  resultId?: Id                        // 报告任务完成 → 报告 ID
  errorMsg?: string
  createTime: string; updateTime: string
}

export interface AnalysisReport {
  id: Id; userId: Id
  reportType: 'daily' | 'weekly' | 'monthly' | 'custom'
  reportTitle: string
  startDate: string; endDate: string   // yyyy-MM-dd
  summary?: string                     // 全文前 200 字
  content?: string                     // GFM Markdown（含表格）
  statData?: string                    // ⚠️ JSON 字符串，非对象（R9）
  taskId?: Id
  createTime: string
  isDeleted?: 0 | 1
}

/** statData 反序列化后的形状 */
export interface ReportStatData {
  sampleCount: number
  tagStats: Array<{ tagCategory: string; tagCode: string; tagName: string; cnt: number }>
  emotionTrend: Array<{ statDay: string; tagName: string; cnt: number }>  // statDay 为 yyyy-MM-dd
}

export interface KnowledgeDoc {
  id: Id; title: string
  category: string                     // 情绪调节 / 心理科普 / 自我成长
  content: string
  chunkCount?: number
  status: 0 | 1 | 2                    // 0 未向量化 1 已向量化 2 已禁用
  createTime: string; updateTime: string
}

export interface LoginResponse { token: string; userId: Id; nickname: string }
```

> 注意每个可选字段的 `?`：因为后端 `non_null` 序列化，运行时真的会缺键。解构时必须给默认值。

### 3.5 必读红线 R1–R13（每一条都对应真实事故，写代码前先看这页）

| 编号 | 事实（来自后端源码） | 必然后果 / 必须的做法 |
|---|---|---|
| **R1** | 所有主键是雪花算法 `Long`（19 位，如 `1942183927384657921`），Jackson 以 **JSON number** 输出，且后端**没有**配 `Long→String` 序列化 | `JSON.parse` 会静默丢精度（末位变 0）→ 详情接口 404、任务/报告关联错乱。全局 `type Id = string`；HTTP 层用 `json-bigint({storeAsString:true})`（§6.1）；**严禁** `Number(id)` / `parseInt(id)`；localStorage 与路由参数一律字符串比较 |
| **R2** | 后端未配置任何 CORS（`WebConfig` 只注册了拦截器） | 开发期必须用 Vite `server.proxy` 把 `/api` 代理到 `8080`；生产期 Nginx 同源反代。前端代码里**不允许**出现 `http://localhost:8080` 字面量，baseURL 用 `import.meta.env.VITE_API_BASE ?? '/api'` |
| **R3** | `AuthInterceptor` 拦截 `/api/**`，仅放行 `/api/auth/**`、`/actuator/**`；Token 存 Redis、**24h 固定过期、无刷新接口**；`logout` 自己读 `Authorization` 头（缺头会 500） | 每个请求（含 logout）都要注入 `Authorization: Bearer`；准备"登录过期"重新登录流程而不是 refresh；长时间停留在对话页时发送前先探活（可用一次轻量 `GET /api/conversations`） |
| **R4** | SSE 是 `GET /api/chat/stream` + **query 参数**，且必须带 `Authorization` 头 | `EventSource` **无法设置请求头**，直接不可用 → 必须 `fetch` + `ReadableStream` 手写解析（§6.3）。响应体是 `Flux<String>` 的裸文本分片，**分片里带 `\n`（Markdown 表格/空行必然带）**，而 SSE 帧内换行的编码方式由 Spring MVC 的 SSE 写回逻辑决定 → 动手写解析器**之前**，先用附录 A 的第 3 条 curl 命令实测一次真实分帧：确认它是拆成多个 `data:` 行（此时解析必须按规范把同帧多个 `data` 值以 `\n` 连接还原），还是裸换行（此时按空行分帧会截断消息，必须上报并改用同步分支）。解析写错的症状是"回复里的表格塌成一行"，属静默错误，所以 §8.2 第 2 条单测为强制项 |
| **R5** | SSE 全程**不回传** `conversationId` / `replyMessageId` / `tags` / `taskId`（元信息只在同步接口 `ChatResponse` 里） | 新会话首条消息必须走同步 `POST /api/chat` 以取得 `conversationId`；SSE 结束后刷新 `GET /api/conversations` 与服务端对齐（本地临时 ID 丢弃，一切以服务端为准） |
| **R6** | SSE 的 `content` 走 URL query，Tomcat 默认 `maxHttpHeaderSize=8KB`（含请求行）；汉字 URL 编码后约 9 字节/字 | 流式分支输入长度上限设 **800 字符**，超出改走同步 `POST /api/chat`（后端 `@Size` 上限 4000）。输入框需提前给出"长内容将以非流式发送"的提示 |
| **R7** | Jackson `default-property-inclusion: non_null` | 后端 null 字段**整个键不存在**。TS 类型必须标 `?`，解构必须给默认值（`const { tags = [] } = res`），禁止 `res.xxx.yyy` 裸链式访问 |
| **R8** | `date-format: yyyy-MM-dd HH:mm:ss`、`time-zone: Asia/Shanghai`；`LocalDate` 为 `yyyy-MM-dd` | 不是 ISO 8601。`new Date('2026-10-11 14:30:00')` 跨引擎不可靠 → 统一 `dayjs(t, 'YYYY-MM-dd HH:mm:ss')`（需 `customParseFormat` 插件），封装成 `TimeText` 组件与 `formatTime()` 一处出 |
| **R9** | `AnalysisReport.statData`、`Task.taskParams` 是**字符串承载的 JSON**（实体字段类型 `String`） | 必须 `safeJsonParse`（try/catch + 形状校验 + 默认值），解析失败降级为"数据不可用"，绝不让页面白屏 |
| **R10** | `Result.code` 是 **6 位字符串**；`GlobalExceptionHandler` 只在 `UNAUTHORIZED` 时返回 401、参数校验失败返回 400、未知异常 500，**其余业务异常一律 HTTP 200** | 判成功的唯一依据是 `code === '000000'`。禁止 `if (res.status === 200)`、禁止 `code === 0`；`src/api/*` 对页面只暴露拆包后的 `data` |
| **R11** | 输入被合规拦截时链路返回 `code=000000` + `replyType='intercepted'`，且无 `tags`/消息 ID | 拦截**不是错误**，不能弹红色 toast，也不能重试。渲染为对话区内的中性提示条（§5.3） |
| **R12** | `AnalysisReport.content` 是 LLM 产出的 **GFM Markdown**（含表格）；对话回复也可能含 Markdown | 渲染链固定为 `markdown-it({ html: false })` → `DOMPurify.sanitize` → `v-html`。关掉 `html` 且必须消毒，不得省略任一环（模型输出属于不可信内容） |
| **R13** | ID 会出现在路径参数、query、localStorage、路由 URL 中 | 全链路以字符串形态存在：路由参数 `String(route.params.id)`，不做任何数值转换或排序（雪花 ID 不可按字符串字典序当作时间序，排序一律用 `createTime`/`lastActiveTime`） |

---

## 4. 业务失败与错误码（`src/types/common.ts` + 拦截器）

### 4.1 必须区分三层错误

| 层 | 判据 | UI 处理 |
|---|---|---|
| 网络层 | `err.code === 'ERR_NETWORK'` / timeout | 顶部 `el-notification`："网络异常，请检查后端是否启动（8080）"，并保留用户输入 |
| 认证层 | HTTP **401** 或 `code === '300001'` | 清 token → `router.replace('/login')`，携带 `redirect` query；**只跳一次**，多个并发 401 需去重 |
| 业务层 | HTTP 200/400/500 且 `code !== '000000'` | 按 §4.3 分类呈现，永远展示 `traceId` 供复制 |

### 4.2 关键错误码（全表见后端 `docs/error.md` 与 `common/ErrorCode.java`，此处只列前端需感知的）

```ts
export const CODE = {
  SUCCESS: '000000',
  PARAM_INVALID: '100001', PARAM_MISSING: '100002', PARAM_FORMAT_ERROR: '109001',
  USERNAME_EXISTS: '200001',
  SESSION_EXPIRED: '201001',           // 会话已结束 → 提示"请新建会话"
  ANALYSIS_LOW_CONFIDENCE: '202001',   // 低置信度 → UI 已用 needClarify 表达，通常不作为错误抛出
  CHAT_REGENERATE_EXCEEDED: '203001',
  RETRIEVAL_EMPTY: '204001',           // 静默：转空状态文案"没找到相关历史，换个时间或说法"
  TASK_STATE_ILLEGAL: '205001',        // 按钮态守卫不足导致 → 刷新列表
  TASK_RETRY_EXCEEDED: '205003',
  COMPLIANCE_INPUT_REJECT: '206001', COMPLIANCE_OUTPUT_REJECT: '206002', COMPLIANCE_HIGH_RISK: '206003',
  PROFILE_DATA_INSUFFICIENT: '207001', REPORT_GENERATE_FAILED: '207002',
  UNAUTHORIZED: '300001', FORBIDDEN: '300002', USER_DISABLED: '300003', LOGIN_FAILED: '300004',
  CONVERSATION_NOT_FOUND: '401001', MESSAGE_NOT_FOUND: '401002',
  TASK_NOT_FOUND: '405001', REPORT_NOT_FOUND: '407001', KNOWLEDGE_DOC_NOT_FOUND: '404001',
  SYSTEM_ERROR: '500001',
  AI_MODEL_ERROR: '600001', EMBEDDING_ERROR: '600002',
  DB_ERROR: '608001', REDIS_ERROR: '608002', MILVUS_ERROR: '608003',
} as const
```

**呈现策略**（避免把所有错误都变成一个红色 toast）：
- `300004 LOGIN_FAILED` / `300003 USER_DISABLED` / `200001 USERNAME_EXISTS` → 表单字段内联报错，不弹全局。
- `206001/206002/206003` → 对话区内的"合规拦截"提示条（`el-alert type="warning"`），文案要中性、不指责用户。
  ⚠️ 大多数情况下输入被拦截时后端返回的是 **`code=000000` + `replyType='intercepted'`**（见 R11），
  这三个码通常只出现在报告/内部链路，不要指望它们来驱动 UI。
- `600001/600002` → "模型服务暂时不可用"，并提供"重试"按钮（LLM 类失败可重试）。
- `608001/608002/608003` → "系统繁忙"，**不**提供重试（非幂等），提示稍后再看。
- `500001` → "系统内部异常"，重点展示 `traceId` + "复制反馈"。

### 4.3 `BizError`

```ts
export class BizError extends Error {
  constructor(readonly code: string, message: string, readonly traceId?: string) { super(message) }
  get kind(): 'auth' | 'compliance' | 'retryable' | 'fatal' | 'empty' | 'invalid' { /* 按 CODE 前缀一位分类 */ }
}
```
按 `code[0]` 分类：`1`=invalid、`2`=业务（`20 6*`=compliance）、`3`=auth、`4`=empty、`5`=fatal、`6`=retryable/infrastructure。

---

## 5. 信息架构与页面规格

### 5.1 路由表

| 路径 | 视图 | 鉴权 | 说明 |
|---|---|---|---|
| `/login` | LoginView | 免 | 登录 |
| `/register` | RegisterView | 免 | 注册，成功后自动登录并跳 `/chat` |
| `/chat` | ChatView | 需 | 新会话（右侧无消息，展示引导语） |
| `/chat/:conversationId` | ChatView | 需 | 指定会话；**参数一律按字符串处理**（R13） |
| `/reports` | ReportListView | 需 | 报告列表 |
| `/reports/:reportId` | ReportDetailView | 需 | 报告详情（Markdown + 图表） |
| `/tasks` | TaskListView | 需 | 任务中心 |
| `/profile` | ProfileView | 需 | 我的画像（接口缺口，见 §5.6） |
| `/knowledge` | KnowledgeView | 需 | 知识库管理（二级入口，放在顶栏"管理"下拉） |
| 其余 | 404 | — | `/:pathMatch(.*)*` → 重定向 `/chat` |

守卫：无 token 且目标需鉴权 → `/login?redirect=<fullPath>`；已有 token 访问 `/login|/register` → `/chat`。
登录成功写 token 后必须 `router.replace(query.redirect ?? '/chat')`。

### 5.2 布局

- **桌面（≥992px）**：左侧固定栏 240px（Logo + 主导航 + 会话历史列表 + 底部用户区），右侧内容区最大宽 1080px 居中。
- **平板（768–992px）**：侧栏折叠为图标条 64px。
- **移动（<768px）**：侧栏改为 `el-drawer`（左上角汉堡按钮唤起），内容区全宽，底部固定输入区。
- 顶栏仅在移动端出现（页面标题 + 返回 + 用户头像）。
- 断点用 `@vueuse/core` 的 `useBreakpoints` 或自写 `useMediaQuery`，**不要**靠 CSS 隐藏造成逻辑分裂。

### 5.3 对话页（核心，工作量最大）

**结构**：会话列表（复用侧栏）｜消息流｜输入区（Composer）。

**数据流**
1. 进入 `/chat/:id` → 并发拉 `GET /api/conversations/{id}/messages`。渲染历史气泡；历史消息**没有标签**（后端消息接口不返回 tag_record，见 §9 B7），因此历史气泡不显示 TagChips，只有当前会话内刚产生的回复才带标签 —— 这是接口限制，不是 bug，UI 别做出"刷新后标签会回来"的暗示。
2. 发送消息 → 走 §5.3「发送策略」。
3. 完成后刷新侧栏会话列表（新会话需要拿到 id 与自动生成的 title）。

**发送策略（解决 R5/R6，务必按此实现）**

```
if (无 conversationId)                      → POST /api/chat（同步），拿到 conversationId 并 router.replace(`/chat/${id}`)
else if (content.length <= 800)             → GET /api/chat/stream（SSE，打字机）
else                                        → POST /api/chat（同步，长文本不发 query）
```
- 800 的由来：SSE 的 `content` 走 URL query，中文 URL 编码后 1 字符占 9 字节，Tomcat 默认 `maxHttpHeaderSize=8KB`（含请求行）→ 约 800 汉字封顶。超出会 400，且报错信息很难懂。
- 同步分支需要展示"Agent 正在思考…"的骨架气泡（后端会跑完整链路：合规→分析→路由→生成→合规→归档，实测 3–20 秒），前端超时设 `timeout: 90_000`，SSE 无超时但要有 45s 无分片的看门狗。

**消息气泡（MessageBubble）**
- 用户消息右对齐，系统消息左对齐。
- 系统气泡下方依次渲染：`TagChips` → `emotionTone` 徽标 → 元信息行（时间 + `replyMessageId` 溯源链接）。
- `replyType` 差异：
  - `chat` / `consult`：普通气泡。`consult` 额外显示"参考知识库"角标（内容已含检索来源文本，不需再请求）。
  - `query`：历史检索结果告知，气泡内保留后端返回的 Markdown 列表格式。
  - `task_notify`：若 `taskId` 存在 → 气泡尾部渲染可点击的"查看任务"按钮，跳 `/tasks?highlight=<taskId>`。
  - `clarify` / `needClarify === true`：气泡用 `el-alert` 风格的引导态（浅底 + 提示图标），并让输入框自动聚焦、placeholder 改为"再具体说说当时的情况？"。
  - `intercepted`：**不是错误**。渲染为居中提示条："这条内容未通过安全校验，我不会据此做任何判断。换个说法或稍后再聊。" 不显示标签、不显示溯源链接（后端未生成消息记录）。
- 高危风险语境（`emotionTone` 为负面且后端回复中已含正向干预话术）：前端**不得**自行判定风险等级（禁止诊断），只在出现 `replyType='intercepted'` 时展示后端给的话术，不额外添加"你可能有抑郁倾向"之类的文案。

**Composer**
- `textarea` 自增高（1–6 行），Enter 发送 / Shift+Enter 换行（移动端两行按钮）。
- 实时字数 `n / 4000`；>800 时提示"长内容将以非流式发送"；>4000 禁止提交（与后端 `@Size` 一致，别等 400）。
- 流式进行中禁用发送，按钮切换为"停止"（`AbortController.abort()`；后端 Flux 会随连接关闭而中断，但**该条回复不会被归档**，需要在 UI 上标注"已停止，本条未保存"并在下次拉历史时以服务端为准）。
- 失败重试：保留输入内容原样回填。

**自动滚动**：新内容且用户原本在底部时贴底；用户上滑则显示"回到底部"浮标，不强吸。

### 5.4 报告列表页
- `GET /api/reports`，前端做 `reportType` 筛选（`el-segmented`）+ 标题/摘要关键字过滤（本地过滤，**后端无搜索接口**）。
- 卡片：标题、类型徽标、统计周期 `startDate ~ endDate`、`summary` 两行截断、创建时间、样本数（需 `JSON.parse(statData).sampleCount`）。
- 点击 → 详情。`statData` 解析失败时仍能打开（只是无图表，显示"统计数据不可用"）。

### 5.5 报告详情页（工作量次高）
- 头部：标题、周期、类型、`taskId` 链接、"该报告基于你在 X 期间的 N 条对话"（`sampleCount`）。
- 图表区（数据源仅来自 `statData`，**不再发额外请求**）：
  1. `StatCards`：样本数、标签种类数（`tagStats.length`）、主导情绪（`tagStats` 中 `category='emotion'` 且 `cnt` 最大者）。
  2. `TagDistChart`：`tagStats` 按 `cnt` 取前 8 + "其他"合并，配色用 `palette.ts` 按 `tagCategory` 分组。
     ~~原方案 `TagPieChart`~~ 已改为横向条形图：饼图在此场景是数据可视化反模式（"A donut/pie for
     comparing close values … ≤6 segments"）——本项目的标签次数天然接近（焦虑 12 / 压力 9 / 低落 7），
     且前 8 + 其他会到 9 片，角度编码比较不出差别；条形以长度编码，顺带让 `tagCategory` 归组可读。
     类别色仍需满足 §7.3 的对比度与色觉安全要求，直接标签（条尾数值）与表格视图两项缓解措施保留为强制要求。
  3. `EmotionTrendChart`：`emotionTrend` 折线/散点，X 轴 `statDay` 升序去重（后端只给"每日主导情绪"，不是全量曲线 → 图表标题写"每日主导情绪"，**不要**写"情绪走势曲线"以免夸大）。
- 正文：`MarkdownViewer` 渲染 `content`（含 GFM 表格）。表格用于呈现"标签/次数/占比"，必须正常渲染。
- 溯源：`tagStats` 每一项 hover 出"查看原始对话"，点击 → 溯源抽屉（§5.7）。
- 底部常驻免责声明（§7.4）。
- `sampleCount` 很小（后端有 `MIN_SAMPLE_COUNT` 概念，`summary` 里会出现"数据有限，结论仅供参考"）→ 当 `sampleCount < 10` 时在图表上方显示 `el-alert` "样本较少，结论仅供参考"。判据用 `summary.includes('数据有限') || sampleCount < 10`，**不要**自己另设阈值语义。

### 5.6 画像页（接口缺口，按规避方案实现）
后端有 `user_profile` 表与 `ProfileService`，但**没有任何 Controller 暴露**（§9 B1）。v1 方案：
- 用 `GET /api/reports` 取最新一条报告的 `statData` 聚合出"当前画像"视图（情绪分布 Top5、心理特征分布、样本量）。
- 页面顶部明确标注："画像接口待后端提供，当前展示的是最近一次报告（<标题/日期>）的统计快照。" —— 诚实标注数据来源，符合"可溯源"硬约束。
- 画像数据加载封装在 `composables/useProfile.ts` 里，**内部先走 report 兜底**；后端补上 `/api/profiles` 后只改这一个文件。
- 禁止在此页做趋势对比（无多版本数据源），不要画假趋势。

### 5.7 溯源抽屉（TraceDrawer）
实现"所有结论可溯源"这条硬约束的载体，全站唯一入口组件。
- 支持三种入参：`{ messageId }`（某条系统/用户消息）、`{ tagCode, category }`（某标签的全部命中）、`{ conversationId }`。
- 数据来源：`GET /api/conversations/{id}/messages` 全量拉取 + 本地按内容/时间过滤 —— 因为后端**没有** `GET /api/messages/{id}/tags`，也**没有**按标签查消息的接口（§9 B7、B8）。
- 因此 v1 的溯源粒度只能做到：**报告结论 → 原始报告 Markdown 表格 → 会话列表 → 原始消息文本**。标签级精确溯源需等后端 B7 落地，届时替换 `useTraceability` 内部实现即可，UI 不变。
- 抽屉里每条消息展示：时间、角色、原文、`vectorId` 是否存在（存在则可标注"已向量化，可被语义检索命中"）。
- 从对话气泡的 `replyMessageId` 进入时，可直接定位到该消息并高亮。

### 5.8 任务中心
- `GET /api/tasks` 列表 + 每 **5 秒轮询**（`useIntervalFn`，页面隐藏时暂停 —— 用 `document.visibilityState` 或 `useDocumentVisibility`；离开路由即停）。
- 行：任务名、类型徽标、状态徽标、`el-progress`（`progress`，`status=2` 时强制 100%）、创建/更新时间、`errorMsg`（失败态展开可见）。
- 操作按钮守卫（前端先判，避免撞 `205001`）：
  - 取消：仅 `status ∈ {0, 1}`；
  - 重试：仅 `status === 3`；
  - 查看结果：`status === 2 && resultId` → 跳 `/reports/{resultId}`。
- `taskParams`（JSON 字符串）解析后展示为描述列表（`reportType/startDate/endDate`），解析失败显示原文，不崩。
- **无子任务步骤接口**（§9 B2）：`SubTask` 的 `stepName`/`status` 拿不到。UI 上只显示 `progress` 数字进度，**不要**画步骤条（会误导用户），并在详情里写"执行步骤明细待后端提供"。
- 深链 `/tasks?highlight=<id>`：进入后滚动到该行并高亮 2s。

### 5.9 知识库管理
- 定位为"内容运营页"，非普通用户主路径，入口放顶栏"管理"下拉。
- 表格列：标题、分类、`chunkCount`、状态徽标（0 待向量化 / 1 已向量化 / 2 已禁用）、更新时间。
- 新增：`el-dialog` 表单，`category` 用 `el-select`（可创建）预置三类：情绪调节 / 心理科普 / 自我成长。
- "触发向量化"按钮 → `POST /api/knowledge/vectorize`，toast 回显返回条数；请求期间按钮 loading 且超时设 `180_000`（Milvus 写入 + 嵌入调用慢）。
- 知识库是**全局共享**内容，页面需注明"该知识库为公共心理科普内容，不含任何用户私有数据"。

---

## 6. 基础设施层设计（先写这层，再写页面）

### 6.1 `api/http.ts`

职责：单例 axios、注入 token、**按 `code` 拆包**、大数安全解析、统一抛 `BizError`、401 去重跳转。

要点实现（务必包含 `transformResponse` 的大数处理）：

```ts
import JSONBig from 'json-bigint'

const bigJson = JSONBig({ storeAsString: true, protoAction: 'remove', constructorAction: 'remove' })

/** R1：把超出安全整数范围的数字（雪花 ID）在读入时转为字符串，
 *  普通数字（progress/confidence/cnt）保持 number。 */
function parseSafe(raw: string): unknown {
  if (!raw) return raw
  try { return bigJson.parse(raw) } catch { return raw }   // 非 JSON（如空体）原样返回
}

// axios.create({ baseURL: import.meta.env.VITE_API_BASE ?? '/api', timeout: 20_000,
//                transformResponse: [parseSafe] })
```

拦截器：
- 请求：有 token 则 `Authorization: Bearer <token>`；生成/透传 `X-Trace-Id`（自增 `crypto.randomUUID().replaceAll('-','')`）。
- 响应：`res.data.code === '000000'` → `return res.data.data`（**页面拿到的直接是业务数据**，不要再解 `.data.data`）；
  否则抛 `BizError(code, message, traceId)`；HTTP 401 或 code `300001` → 走认证失效处理（清 token、`router.replace`、只跳一次）。
- 网络层错误转 `BizError('NETWORK', ...)`，UI 层不区分 608001 之类的具体基础设施码给普通用户看。

### 6.2 `api/*.ts` 函数签名（全部 `Promise<T>` 且 `T` 已是拆包后的 data）

```ts
// auth.ts
login(p: { username: string; password: string }): Promise<LoginResponse>
register(p: { username: string; password: string; nickname?: string }): Promise<Id>
logout(): Promise<void>
// chat.ts
sendChat(p: { content: string; conversationId?: Id }): Promise<ChatResponse>
// conversation.ts
listConversations(): Promise<Conversation[]>
listMessages(conversationId: Id): Promise<Message[]>
closeConversation(conversationId: Id): Promise<void>
// report.ts
listReports(): Promise<AnalysisReport[]>
getReport(reportId: Id): Promise<AnalysisReport>
// task.ts
listTasks(): Promise<Task[]>
cancelTask(taskId: Id): Promise<void>
retryTask(taskId: Id): Promise<void>
// knowledge.ts
listDocs(): Promise<KnowledgeDoc[]>
addDoc(p: { title: string; category: string; content: string }): Promise<Id>
vectorizePending(): Promise<number>
```

### 6.3 `api/sse.ts`（最容易写错的地方）

**必须用 `fetch`，禁止 EventSource** —— EventSource 不能设置 `Authorization` 头（R4）。
分帧格式**不许凭印象写**：先执行附录 A 第 3 条 curl，拿一份真实响应样本存进 `mocks/fixtures/sse-raw.txt`，
再据此实现解析器，并把该样本直接作为单测输入（§8.2 第 2 条）。
规范实现按"空行分帧 + 同帧多 `data:` 行以 `\n` 连接"编写（这是 SSE 标准语义，也是 Spring 的常见输出形态）；
若实测发现分片里的裸换行会破坏分帧，停止并上报，不要自创"猜测式"解析。

```ts
export interface StreamHandlers {
  onDelta: (text: string) => void
  onDone: () => void
  onError: (e: BizError | Error) => void
}

export async function streamChat(
  p: { content: string; conversationId?: Id; signal: AbortSignal },
  h: StreamHandlers,
): Promise<void> {
  const qs = new URLSearchParams({ content: p.content })
  if (p.conversationId) qs.set('conversationId', p.conversationId)

  const res = await fetch(`/api/chat/stream?${qs}`, {
    headers: { Accept: 'text/event-stream', Authorization: `Bearer ${getToken()}` },
    signal: p.signal,
  })

  // 关键：鉴权失败/参数错误时后端返回的是 JSON 而非 SSE，必须先分流
  const ct = res.headers.get('content-type') ?? ''
  if (!res.ok || !ct.includes('text/event-stream')) {
    h.onError(await toBizError(res, ct))   // 解析 Result 拿 code/message/traceId
    return
  }

  const reader = res.body!.getReader()
  const decoder = new TextDecoder('utf-8')
  let buf = ''
  for (;;) {
    const { value, done } = await reader.read()
    if (done) break
    buf += decoder.decode(value, { stream: true })
    let idx: number
    while ((idx = indexOfFrameEnd(buf)) >= 0) {        // 以空行分帧（\n\n 或 \r\n\r\n）
      const frame = buf.slice(0, idx); buf = buf.slice(idx + frameSepLen(buf, idx))
      const data = parseFrame(frame)                   // 收集所有 data: 行 → 去前缀与一个前导空格 → join('\n')
      if (data) h.onDelta(data)
    }
  }
  const tail = parseFrame(buf); if (tail) h.onDelta(tail)
  h.onDone()                                            // 无 [DONE] 哨兵：流关闭即完成
}
```

`parseFrame` 规则：忽略以 `:` 开头的注释行、`event:`/`id:`/`retry:` 字段；`data:` 去掉键与冒号后**只剥离一个前导空格**；多行 `data` 用 `\n` join。空 `data:` 行代表一个换行，不能过滤掉。

必须实现的配套逻辑（放 `composables/useChatStream.ts`）：
- `AbortController` 暴露"停止"；`abort` 后不得再回调。
- 45s 无分片的看门狗 → 报错并提示重试。
- 错误兜底文案：后端在中断时会下发固定串 `（生成中断：服务暂时不可用，请稍后重试）`，前端识别到它就不再叠加自己的错误气泡。
- **流结束后必须做两件事**：(a) 侧栏会话列表刷新（SSE 不返回 conversationId/标题/消息 ID，见 R5）；
  (b) 把本地乐观追加的消息与服务端 `listMessages` 结果对齐（以服务端为准，丢弃本地临时 ID）。

### 6.4 Stores

```ts
// stores/auth.ts —— 唯一持有 token
state: token / userId / accountName（后端不回传真实昵称，见 §3.3 #2）
actions: login(), register(), logout(), clear()
持久化：localStorage key `mirror.token`（useLocalStorage）；userId 每次启动由本地缓存读取
注意：logout 要调用后端 #3 接口（带 Authorization），失败也要清本地

// stores/conversation.ts
state: list, activeId, messagesByConversation: Map<Id, Message[]>, loading/error
getters: sorted（按 lastActiveTime 倒序）
actions: fetchList(), fetchMessages(id), close(id), invalidate(id)

// stores/chat.ts
state: sending, streaming, draft（按会话草稿 Map<Id,string>）, lastResponse
actions: send(content) —— 内部按 §5.3 发送策略分派，向 UI 暴露统一 delta 事件
```

Store 里**不做 toast**，只暴露状态与错误对象；错误呈现由视图层决定（避免跳转时闪现多个 toast）。

---

## 7. 数据字典、色板与视觉

### 7.1 `constants/dict.ts` 必须包含的映射（照抄后端语义）

```ts
export const TAG_CATEGORY = { emotion: '情绪', intent: '意图', psychology: '心理特征', task: '任务' } as const

export const EMOCTION_DICT: Record<string, string> = {
  happy: '开心', calm: '平静', excited: '兴奋', grateful: '感激', hopeful: '期待', relaxed: '放松',
  anxious: '焦虑', down: '低落', angry: '愤怒', tired: '疲惫', lonely: '孤独',
  stressed: '压力', sad: '悲伤', confused: '迷茫',
}
export const POSITIVE_EMOTION = new Set(['happy', 'calm', 'excited', 'grateful', 'hopeful', 'relaxed'])

export const INTENT_DICT = { chat: '日常闲聊', consult: '建议咨询', query: '历史查询', task: '任务指令' } as const
export const PSYCH_DICT = {
  internal_attribution: '内归因', external_attribution: '外归因', perfectionism: '完美主义',
  low_self_esteem: '低自尊', positive_coping: '积极应对', avoidance_coping: '回避应对',
  catastrophizing: '灾难化思维',
} as const
export const TASK_TAG_DICT = {
  daily_report: '日报', weekly_report: '周报', monthly_report: '月报',
  custom_report: '自定义报告', statistic: '数据统计',
} as const

export const TASK_STATUS = { 0: '待执行', 1: '执行中', 2: '已完成', 3: '失败', 4: '已取消' } as const
export const SUBTASK_STATUS = { 0: '待执行', 1: '执行中', 2: '已完成', 3: '失败' } as const  // 预留 B2
export const CONVERSATION_STATUS = { 0: '进行中', 1: '已结束' } as const
export const MESSAGE_ROLE = { 0: 'user', 1: 'system' } as const
export const KNOWLEDGE_STATUS = { 0: '待向量化', 1: '已向量化', 2: '已禁用' } as const
export const REPORT_TYPE = { daily: '日报', weekly: '周报', monthly: '月报', custom: '自定义报告' } as const
export const REPLY_TYPE = {
  chat: '对话', consult: '建议咨询', query: '历史查询',
  task_notify: '任务通知', clarify: '待澄清', intercepted: '已拦截',
} as const
```

标签展示文案纪律：`psychology` 类标签必须带"倾向"语境渲染（如"完美主义倾向"），
渲染函数 `tagLabel(tag)` 统一处理；`needConfirm === true` 的标签渲染为虚线边 + 后缀"（待确认）"，
且 tooltip 说明"置信度较低，仅用于引导对话，不计入画像与报告"。

### 7.2 视觉基调

产品是私密的自我记录工具，**不是后台管理系统**。目标感受：安静、被接纳、低刺激。

- 主色：墨绿 `#3F5D53`（沉稳、去医疗感）；强调色 `#C2703D`（陶土橙，用于"生成中/行动点"）；
  背景 `#F7F5F1`（暖米白，禁纯白刺眼）；正文 `#2A2A28`；分隔线 `#E7E3DB`。
- 深色模式（v1 可选，若实现则所有 token 走 CSS 变量，禁止硬编码色值）。
- 字体：中文 `system-ui, "PingFang SC", "HarmonyOS Sans SC", "Microsoft YaHei"`；
  正文行高 **1.75**（对话内容可读性优先）；字号阶梯 12/13/14/16/20/24；数字用 `font-variant-numeric: tabular-nums`。
- 圆角 12（气泡 14，单角 4 制造方向感）；阴影只用 `0 1px 2px rgba(0,0,0,.04)` 级别。
- Element Plus 变量覆盖集中在 `styles/element-overrides.css`（`--el-color-primary` 等），不在组件里散写 `style`。
- 动效：仅在流式打字、抽屉、状态切换处使用；`prefers-reduced-motion: reduce` 时关闭所有非必要动画。

**不要**：默认 Element 蓝铺满全站、渐变紫"AI 风"背景、emoji 当图标、大面积卡片阴影、把情绪用红/绿做对错暗示。

### 7.3 `constants/palette.ts`（图表与标签配色）

- 按 `tagCategory` 分组基色：emotion 陶土系、intent 灰蓝系、psychology 灰紫系、task 墨绿系；
  每组内用同色系明度阶梯区分个体，**保证同组内可区分、组间可归类**。
- 情绪正向/负向只做明度差异，不做红绿对立（避免价值判断）。
- 色觉安全：图表不依赖颜色单点传达信息 —— 分布条形必须**条尾直接标数值**且保留 `legend`（按 `tagCategory` 四组），
  趋势图不同情绪用不同 `symbol` 形状；两张图都必须有等价的表格视图。
- 类别色的取值必须**跑校验脚本得到**，不许目测：明度带、彩度下限、相邻色觉分离度（OKLab ΔE ≥ 8）、
  正常视力可辨度（ΔE ≥ 15）、对底色对比度 ≥ 3:1 五项全过才可落地。
  四个类别的**显示顺序**同属色觉安全机制（陶土与绿在 protanopia 下会塌陷，必须放到互为对位的位置），
  重排顺序或改色值后必须重跑校验。
- 所有色值与相邻底色对比度 ≥ 3:1（图元）、正文文本 ≥ 4.5:1。
- 落地为纯 TS 常量导出 + CSS 变量，图表与标签共用同一份，不允许出现第二处硬编码。

### 7.4 免责声明（硬约束 1 的 UI 落地）

文案（报告详情底部、画像页底部常驻，小字灰度，不抢视觉）：

> 以上内容基于你自己的对话记录统计生成，仅是情绪与表达倾向的描述，**不构成任何医学或心理学诊断**。
> 如你正处于危机中，请联系专业机构或身边信任的人。

对话页底部输入区上方常驻一行更短的提示："这里的分析只是对你表达倾向的记录，不是诊断。"

---

## 8. 质量门槛与验收

### 8.1 静态检查必须通过
```bash
pnpm vue-tsc --noEmit      # 0 error（Vue 官方强要求，不可跳过）
pnpm eslint . --max-warnings 0
pnpm build                 # 成功；产物 gzip 后单 chunk < 300KB，路由级懒加载
```

### 8.2 必写的 5 个单元测试（Vitest）—— 全部针对"会静默出错"的地方
1. `http.spec.ts`：`Result` 拆包；`code!=='000000'` 抛 `BizError`；401 清 token 且只跳一次。
2. **`sse.spec.ts`**：还原文本必须与原始回复**逐字符相等**。样本一律取自 `mocks/fixtures/sse-raw.txt`（curl 实测抓取，
   不许手写理想化样本），并额外构造三种边界：分片内多行 `data:`、chunk 在帧中间断开、结尾无换行即关闭。
   （这条是本项目的核心回归测试——解析器写错是静默错误，肉眼看不出来。）
3. **`bigint.spec.ts`**：`{"data":{"id":1942183927384657921}}` 经 `parseSafe` 后 `id === '1942183927384657921'`
   且**末位不为 0**（防止精度丢失成 `...920`）；同时 `progress: 60` 仍是 `number`。
4. `statData.spec.ts`：合法 JSON 解析、非法 JSON 返回 `null` 不抛、缺字段时给默认空数组。
5. `dict.spec.ts`：所有 `status`/`type` 映射覆盖全部取值，未命中回退 `'未知(值)'` 而非 `undefined`。

### 8.3 手工验收清单（提交前自查）
- [ ] 用真实后端跑通：注册 → 登录 → 发一条含"焦虑"的消息 → 看到标签 chips → 刷新页面历史仍在。
- [ ] SSE 打字机输出中的 Markdown 表格能正确渲染（说明换行没丢）。
- [ ] 发 1200 字长内容走同步分支，不报错。
- [ ] 手动把 localStorage 的 token 改成乱码 → 任意请求 → 跳登录，无死循环、无 5 个 toast。
- [ ] 触发一次合规拦截（输入敏感词）→ 看到拦截提示条，且**不是**红色报错。
- [ ] 报告页在无 `statData` 时仍可阅读正文。
- [ ] 移动端 375px 宽：侧栏成抽屉、输入区不遮挡消息、图表不溢出。
- [ ] 全站无 `console.log`、无 `any`、无 `@ts-ignore`、无 >300 行的 .vue。

### 8.4 Mock（后端不 always-on，需可脱离后端演示）
- `mocks/adapter.ts`：用 axios 自定义 `adapter` 按路由返回 fixtures；由 `VITE_USE_MOCK=1` 开启，生产构建剔除。
- fixtures **必须严格贴合 §3/§4 契约**，包含：`non_null` 导致的缺键、雪花 ID 大数、`statData` 字符串、
  `replyType='intercepted'` 与 `needClarify=true` 各一条、`status=3` 带 `errorMsg` 的任务各一条。
  用假数据"美化"契约 = 联调时全线崩，禁止。

---

## 9. 后端补丁清单（前端不阻塞，另起提示词执行）

前端已按现有接口实现，以下为**建议后端补齐**的项。补齐后再回来替换对应 composable 的内部实现，UI 不动。

| 编号 | 缺失能力 | 建议接口 | 前端当前的规避做法 |
|---|---|---|---|
| B1 | 用户画像无接口（`ProfileService.getLatest/listVersions` 已实现但未暴露） | `GET /api/profiles/latest`、`GET /api/profiles/versions` | 画像页用最新报告的 `statData` 兜底，并诚实标注来源（§5.6） |
| B2 | 任务子步骤无接口（`SubTask` 存在） | `GET /api/tasks/{taskId}/subtasks` | 只显示百分比进度，不画步骤条 |
| B3 | SSE 不回传 `conversationId`/`replyMessageId`/`tags` | 首选新增 `POST /api/chat/stream`（参数走 body）并在流首帧发 `event: meta` 带 JSON 元信息；次选在流末尾补发 meta 帧 | 新会话首条走同步接口；流结束后刷新会话列表对齐 |
| B4 | 雪花 ID 精度风险（`Long` 以 JSON number 输出） | 在 `WebConfig` 增加 `Jackson2ObjectMapperBuilderCustomizer`，对 `Long/long` 全局 `ToStringSerializer` | 前端 `json-bigint(storeAsString)` 兜底（§6.1） |
| B5 | 未配置 CORS | 若前端独立域名部署，需 `addCorsMappings`（allowCredentials + 暴露 `X-Trace-Id`） | v1 用 Vite dev proxy，生产同源反代，**不需要后端改** |
| B6 | 会话不可重命名/删除，且不分页 | `PUT /api/conversations/{id}`（title）、`DELETE /api/conversations/{id}`、列表加 `page/size` | 会话多了以后左侧长滚动；消息全量拉取在大会话下变慢 |
| B7 | 消息标签无接口（`TagService.listByMessage` 已实现未暴露） | `GET /api/messages/{messageId}/tags`、`GET /api/tags?tagCode=&from=&to=` | 历史气泡不显示标签；溯源只到会话级（§5.7） |
| B8 | 报告/任务/会话列表无分页与筛选 | 各列表接口加 `page,size,type,from,to` | 前端本地过滤 |

**给后端提补丁时的约束**（沿用后端 CI 规则，写在补丁提示词里）：
依赖方向 `domain→config→mapper→service→controller`；单文件 ≤300 行、单方法 ≤50 行；
构造器注入（`@RequiredArgsConstructor`），禁止字段级 `@Autowired` 与 `new` 实例化横切组件；
SLF4J 日志，禁止 `System.out.println`/`e.printStackTrace()`；返回统一 `Result<T>`，
新错误码遵循 `docs/error.md` 的 6 位编码；新增接口必须走 `AuthInterceptor` 且不泄露他人数据（按 `user_id` 校验归属）。

---

## 10. 分阶段执行指令（逐条复制给 Claude Code）

> 每阶段结束后自行跑 §8.1 的三条命令，通过再进入下一阶段。**任何阶段不要顺手实现下一阶段的东西。**

### Stage 0 · 初始化
```
在 D:\project\self-mirror-agent-web 创建 Vue3 + Vite + TS 项目骨架。
严格按 docs/1frontend_generation_prompt.md 的 §2 落地目录结构、tsconfig(strict)、ESLint/Prettier、
vite.config.ts（必须配 `server.proxy`：`'/api'` → `http://localhost:8080`，`changeOrigin: true`，
     因为后端未配置 CORS，见 §9 B5）、
.env.development / .env.production。
依赖用 pnpm 安装；安装前先 npm view 校验当前稳定主版本，不要凭记忆钉版本。
本阶段不写任何业务代码，只产出可 `pnpm dev` 打开的空壳首页。
```

### Stage 1 · 基础设施层（最重要，必须先稳）
```
实现 §3.4 全部类型、§4 错误码与 BizError、§6.1 http.ts、§6.2 api/*.ts、§6.3 sse.ts、
§7.1 dict.ts、§7.3 palette.ts、§7.2 的 styles/*、§6.4 三个 stores、§5.1 路由与守卫、§5.2 AppLayout。
重点红线：R1（雪花 ID 精度）、R4/R5（SSE 用 fetch、EventSource 禁用、无 meta）、
R7（non_null 缺键）、R8（日期格式）、R9（statData/taskParams 是字符串）、R10（code 是字符串且 HTTP 200 也可能是业务失败）。
同时写 §8.2 的 5 个单测并全部跑通。此阶段不写任何视图页面内容。
```

### Stage 2 · 登录注册
```
实现 LoginView / RegisterView，按 §3.3 #1–#3、§4.2 呈现策略（登录失败内联报错，不弹全局 toast）。
校验规则与后端 @Valid 完全一致（username 3–32，password 8–64，nickname ≤32）。
成功后存 token 并 replace 到 redirect 目标。补齐"后端未启动"的引导态。
```

### Stage 3 · 对话页
```
实现 ChatView + components/chat/* + composables/useChatStream.ts，严格遵循 §5.3（含发送策略三段式、
800 字阈值、停止生成、看门狗、流后对齐）与 §6.3。
历史气泡不显示标签（接口限制）。replyType 六种分支全部实现，intercepted 不是错误。
底部常驻短免责声明（§7.4）。本阶段完成后必须用真实后端手工验收 §8.3 前 5 项。
```

### Stage 4 · 报告
```
实现 ReportListView / ReportDetailView + components/report/*，按 §5.5。
statData 解析失败必须降级而非白屏；图表按 §7.3 配色与无障碍规则；
GFM Markdown 用 markdown-it(html:false) + DOMPurify（R12）；样本不足提示；免责声明。
```

### Stage 5 · 任务中心
```
实现 TaskListView + components/task/*，按 §5.8。轮询 5s、页面隐藏暂停、离开路由停止；
按钮状态守卫按 §5.8；不画步骤条（B2 缺口）；taskParams 容错；支持 ?highlight= 深链。
```

### Stage 6 · 知识库
```
实现 KnowledgeView，按 §5.9。新增表单 + 向量化按钮（超时 180s）+ 状态徽标 + "公共知识库"说明。
```

### Stage 7 · 画像与溯源
```
实现 ProfileView（§5.6 兜底方案，数据加载只经 useProfile.ts 一处，便于后端 B1 落地后替换）
与 TraceDrawer（§5.7，能力边界要如实呈现，不做无依据的标签级溯源）。
```

### Stage 8 · 打磨与交付
```
按 §7.2/§7.3 做视觉统一（Element Plus 变量集中覆盖）、移动端 375px 适配、
prefers-reduced-motion、§8.3 全清单自查、§8.1 三条命令 0 error、
§8.4 mock 开关与 fixtures（契约含缺键/大数/字符串 JSON/拦截态）。
最后生成 README.md：启动方式、环境变量、Vite proxy 说明、与后端联调步骤、已知接口缺口清单（引用 §9）。
```

---

## 附录 A · 联调自检命令

```bash
# 1. 注册 + 登录
curl -s -X POST http://localhost:8080/api/auth/register -H 'Content-Type: application/json' \
  -d '{"username":"demo","password":"demo123456","nickname":"Demo"}'
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"demo","password":"demo123456"}' | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')

# 2. 同步对话（拿 conversationId / tags / statData 的真实形状）
curl -s -X POST http://localhost:8080/api/chat -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"content":"最近工作压力好大，晚上睡不着"}'

# 3. SSE（观察分帧与换行丢失问题；-N 关闭缓冲）
curl -N "http://localhost:8080/api/chat/stream?content=%E6%88%91%E5%BE%88%E7%84%A6%E8%99%91" \
  -H "Authorization: Bearer $TOKEN" -H 'Accept: text/event-stream'

# 4. 报告（确认 statData 是被转义的字符串）
curl -s http://localhost:8080/api/reports -H "Authorization: Bearer $TOKEN" | head -c 600
```

## 附录 B · 高频踩坑速查

| 症状 | 根因 | 修法 |
|---|---|---|
| ID 末位变成 0、跳详情 404 | `JSON.parse` 精度丢失 | R1：`json-bigint(storeAsString)`；禁止 `Number(id)` |
| 流式回复里表格/换行全没了 | SSE 多 `data:` 行未 join | R4：按规范空行分帧 + `data` 行以 `\n` 连接 |
| 流式一直"连接中"不结束 | 等 `[DONE]` | 后端无哨兵，`reader.done` 即完成 |
| 长中文输入报 400 且无提示 | query 超长撞 Tomcat 8KB | 800 字阈值切同步分支（§5.3） |
| 刷新后标签消失 | 消息接口不返回 tag_record | 契约限制（B7），UI 不做恢复暗示 |
| 页面出现 5 个红色 toast | 并发请求各自弹 | 错误呈收敛到视图层 + 同类合并去重 |
| `new Date(t)` 得 Invalid | `yyyy-MM-dd HH:mm:ss` 非 ISO | R8：`dayjs(t, 'YYYY-MM-dd HH:mm:ss')` |
| 图表崩在 `statData.xxx.map` | `statData` 是字符串 | R9：`safeJsonParse<ReportStatData>` + 默认值 |
| 业务失败却走 `then` | 只看 HTTP 状态 | R10：拦截器按 `code` 拆包 |
| 登录后立刻 300001 | 忘记带 Bearer 头 | 拦截器注入；logout 也需带 |
| 部署后接口 404 | 生产无 proxy | 生产需 Nginx 反代 `/api` → 后端（或后端补 CORS，见 B5） |
