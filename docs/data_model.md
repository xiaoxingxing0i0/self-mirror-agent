
## 四、系统数据模型设计

### 4.1 整体存储架构

系统采用**MySQL 存储结构化业务数据 + Milvus 存储向量数据 + Redis 缓存会话状态**的混合存储架构。所有业务数据按`user_id`维度隔离，保障用户数据隐私。

核心实体关系：

- 1 个用户 → 多个会话
- 1 个会话 → 多条对话消息
- 1 条消息 → 多条标签记录
- 1 个用户 → 多个任务
- 1 个任务 → 多个子任务
- 1 个用户 → 多版用户画像
- 1 个用户 → 多份分析报告

### 4.2 核心数据表设计（MySQL）

#### 4.2.1 用户表（sys_user）

表格

| 字段名 | 类型 | 主键 | 说明 |
| --- | --- | --- | --- |
| id | bigint | 是 | 用户 ID，雪花算法生成 |
| username | varchar(64) | 否 | 登录用户名 |
| nickname | varchar(64) | 否 | 用户昵称 |
| password | varchar(128) | 否 | 加密密码 |
| status | tinyint | 否 | 账号状态：0 正常 1 禁用 |
| create_time | datetime | 否 | 创建时间 |
| update_time | datetime | 否 | 更新时间 |
| is_deleted | tinyint | 否 | 逻辑删除标记 |

#### 4.2.2 会话表（conversation）

表格

| 字段名 | 类型 | 主键 | 说明 |
| --- | --- | --- | --- |
| id | bigint | 是 | 会话 ID |
| user_id | bigint | 否 | 关联用户 ID |
| title | varchar(255) | 否 | 会话标题（自动生成） |
| start_time | datetime | 否 | 会话开始时间 |
| last_active_time | datetime | 否 | 最后活跃时间 |
| status | tinyint | 否 | 会话状态：0 进行中 1 已结束 |
| message_count | int | 否 | 消息总数 |
| create_time | datetime | 否 | 创建时间 |
| update_time | datetime | 否 | 更新时间 |

#### 4.2.3 对话消息表（message）

表格

| 字段名 | 类型 | 主键 | 说明 |
| --- | --- | --- | --- |
| id | bigint | 是 | 消息 ID |
| conversation_id | bigint | 否 | 关联会话 ID |
| user_id | bigint | 否 | 关联用户 ID |
| role | tinyint | 否 | 角色：0 用户 1 系统 |
| content | text | 否 | 消息内容原文 |
| vector_id | varchar(64) | 否 | 关联 Milvus 向量 ID |
| create_time | datetime | 否 | 消息发送时间 |
| is_deleted | tinyint | 否 | 逻辑删除标记 |

#### 4.2.4 标签记录表（tag_record）

表格

| 字段名 | 类型 | 主键 | 说明 |
| --- | --- | --- | --- |
| id | bigint | 是 | 标签记录 ID |
| message_id | bigint | 否 | 关联消息 ID |
| user_id | bigint | 否 | 关联用户 ID |
| tag_category | varchar(32) | 否 | 标签大类：emotion/intent/psychology/task |
| tag_code | varchar(64) | 否 | 标签编码 |
| tag_name | varchar(64) | 否 | 标签名称 |
| confidence | decimal(3,2) | 否 | 置信度 0-1 |
| create_time | datetime | 否 | 创建时间 |

#### 4.2.5 任务表（task）

表格

| 字段名 | 类型 | 主键 | 说明 |
| --- | --- | --- | --- |
| id | bigint | 是 | 任务 ID |
| user_id | bigint | 否 | 关联用户 ID |
| task_type | varchar(32) | 否 | 任务类型：report/statistic/profile_update |
| task_name | varchar(128) | 否 | 任务名称 |
| task_params | json | 否 | 任务参数（JSON 格式） |
| status | tinyint | 否 | 状态：0 待执行 1 执行中 2 已完成 3 失败 4 已取消 |
| cron_expression | varchar(64) | 否 | 周期任务 Cron 表达式 |
| progress | int | 否 | 任务进度 0-100 |
| result_id | bigint | 否 | 关联结果 ID（如报告 ID） |
| error_msg | varchar(512) | 否 | 失败错误信息 |
| create_time | datetime | 否 | 创建时间 |
| update_time | datetime | 否 | 更新时间 |

#### 4.2.6 子任务表（sub_task）

表格

| 字段名 | 类型 | 主键 | 说明 |
| --- | --- | --- | --- |
| id | bigint | 是 | 子任务 ID |
| task_id | bigint | 否 | 关联主任务 ID |
| step_order | int | 否 | 执行顺序 |
| step_name | varchar(64) | 否 | 步骤名称 |
| status | tinyint | 否 | 状态：0 待执行 1 执行中 2 已完成 3 失败 |
| retry_count | int | 否 | 已重试次数 |
| create_time | datetime | 否 | 创建时间 |
| update_time | datetime | 否 | 更新时间 |

#### 4.2.7 用户画像表（user_profile）

表格

| 字段名 | 类型 | 主键 | 说明 |
| --- | --- | --- | --- |
| id | bigint | 是 | 画像 ID |
| user_id | bigint | 否 | 关联用户 ID |
| version | int | 否 | 画像版本号 |
| stat_date | date | 否 | 统计截止日期 |
| emotion_feature | json | 否 | 情绪特征数据 |
| psychology_feature | json | 否 | 心理特征数据 |
| behavior_feature | json | 否 | 行为模式数据 |
| sample_count | int | 否 | 样本消息数量 |
| create_time | datetime | 否 | 创建时间 |

#### 4.2.8 分析报告表（analysis_report）

表格

| 字段名 | 类型 | 主键 | 说明 |
| --- | --- | --- | --- |
| id | bigint | 是 | 报告 ID |
| user_id | bigint | 否 | 关联用户 ID |
| report_type | varchar(32) | 否 | 类型：daily/weekly/monthly/custom |
| report_title | varchar(255) | 否 | 报告标题 |
| start_date | date | 否 | 统计开始日期 |
| end_date | date | 否 | 统计结束日期 |
| summary | text | 否 | 报告核心摘要 |
| content | longtext | 否 | 报告完整内容（Markdown） |
| stat_data | json | 否 | 统计指标数据（供图表渲染） |
| task_id | bigint | 否 | 关联任务 ID |
| create_time | datetime | 否 | 创建时间 |
| is_deleted | tinyint | 否 | 逻辑删除标记 |

#### 4.2.9 RAG 知识库表（knowledge_doc）

表格

| 字段名 | 类型 | 主键 | 说明 |
| --- | --- | --- | --- |
| id | bigint | 是 | 文档 ID |
| title | varchar(255) | 否 | 文档标题 |
| category | varchar(64) | 否 | 分类：情绪调节 / 心理科普 / 自我成长 |
| content | text | 否 | 文档原文 |
| chunk_count | int | 否 | 分块数量 |
| status | tinyint | 否 | 状态：0 未向量化 1 已向量化 2 已禁用 |
| create_time | datetime | 否 | 创建时间 |
| update_time | datetime | 否 | 更新时间 |

### 4.3 向量数据设计（Milvus）

#### 4.3.1 对话向量集合（message_vector）

- 集合名称：`message_vector`
- 主键：`message_id`（与 MySQL 消息表 ID 一一对应）
- 向量字段：`embedding`（维度由嵌入模型决定，通常 1536 维）
- 标量字段：`user_id`、`conversation_id`、`create_time`
- 分区键：`user_id`（按用户分区分片，提升检索效率）

#### 4.3.2 知识库向量集合（knowledge_vector）

- 集合名称：`knowledge_vector`
- 主键：`chunk_id`（分块唯一 ID）
- 向量字段：`embedding`
- 标量字段：`doc_id`、`category`、`chunk_index`
- 分区键：`category`（按文档分类分区）

### 4.4 核心数据流转链路

1. **对话写入链路**：用户发消息 → 生成会话 / 消息记录 → 语言分析生成标签 → 写入标签记录表 → 异步生成向量写入 Milvus
2. **画像更新链路**：每日定时触发 → 拉取当日标签数据 → 聚合统计各维度指标 → 生成新版用户画像 → 写入画像表
3. **报告生成链路**：触发报告任务 → 拉取时间范围内消息 + 标签 + 画像数据 → 统计指标计算 → 大模型生成报告 → 写入报告表 → 更新任务状态