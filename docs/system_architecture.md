# 系统架构
**架构类型**：动态规划驱动多 Agent 系统
## 整体架构说明
系统采用**中心化调度 + 职能化 Agent 分工**的架构，
以 DP 动态规划调度 Agent 作为全局管控唯一中转节点，
其余 Agent 为专项能力执行节点，所有节点间交互均通过调度 Agent 转发，
实现职责解耦、流程可控与状态统一管理。
## 核心能力
- 日常自然对话交互与共情回应
- 实时细粒度情绪与心理标签提取
- 全量对话与标签数据持久化归档
- 历史对话与人格特征多维度回溯
- RAG 知识库驱动的正向心理建议输出
- 定制化情绪 / 心理分析报告自动生成
- 全链路内容安全与专业合规校验
## 业务流程示例
```mermaid
flowchart TD
    Start([用户请求]) --> Controller["Controller Agent<br/>接收用户请求并转发"]
    Controller --> InputCheck{"合规 Agent<br/>输入内容合规检查"}
    InputCheck -->|不合规| Reject([拒绝回答])
    InputCheck -->|合规| LangAgent["语言分析 Agent<br/>判断用户语义倾向"]

    LangAgent --> TypeSwitch{"语义类型判断"}

    TypeSwitch -->|"日常闲聊"| ChatAgent["聊天 Agent"]
    TypeSwitch -->|"情绪倾向"| EmotionHandler["情绪处理<br/>安抚 / 共情"]
    TypeSwitch -->|"完成任务"| TaskAgent["任务 Agent"]

    EmotionHandler --> ChatAgent

    TaskAgent --> CreateTask[("MySQL<br/>创建任务记录")]
    CreateTask --> ChatAgent

    ChatAgent --> OutputCheck{"合规 Agent<br/>输出内容合规检查"}
    OutputCheck -->|不合规| Reject
    OutputCheck -->|合规| Reply([回复用户])

    Cron([定时任务触发]) --> TaskAgent
    TaskAgent --> SplitTask["拆分任务步骤<br/>任务状态管理"]
    SplitTask --> ToolAgent["工具 Agent<br/>生成文件 / 发送邮件等"]
    ToolAgent --> OutputCheck
```

## 项目目录结构
```
self‑mirror‑agent
├── src/main/java/com/mirror/agent
│   ├── config                    // Spring-AI / Milvus / Redis 配置Bean
│   ├── dp                        // DP调度核心包
│   │   ├── entity                // 任务状态、AgentExecutionPlan、SubTask实体
│   │   └── scheduler             // DpScheduleAgent 调度核心类
│   ├── agent                     // 所有Agent定义
│   │   ├── resident              // 常驻Agent实现
│   │   └── temp                  // 动态临时Agent实现
│   ├── message                   // Agent通信消息POJO
│   ├── tool                      // 工具层全部实现
│   ├── repository                // Mybatis Mapper，Milvus向量库封装
│   ├── entity                    // 数据库实体类
│   ├── service                   // 上层业务服务
│   └── util                      // 通用工具、Markdown报告渲染工具
├── src/main/resources
│   ├── application.yml
│   └── db/migration              // flyway *.sql 迁移脚本
├── docker
│   └── docker‑compose.yml        // 开发环境：mysql8, redis7, milvus
├── pom.xml
└── README.md
```