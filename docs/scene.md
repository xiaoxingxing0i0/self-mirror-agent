### 典型场景执行链路

#### 场景 1：日常对话交互

1. 用户输入 → DP 调度 Agent → 合规检查 Agent（输入校验）
2. 校验通过 → DP 调度 Agent → 语言分析 Agent（语义解析 + 打标签）
3. 标签返回 → DP 调度 Agent → 用户对话 Agent（生成共情回复）
4. 回复生成 → DP 调度 Agent → 合规检查 Agent（输出校验）
5. 校验通过 → DP 调度 Agent → 返回用户，同时归档对话与标签数据

#### 场景 2：心理建议咨询

1. 用户输入建议需求 → DP 调度 Agent → 合规检查 → 语言分析 Agent（打建议标签）
2. DP 调度 Agent → 检索 Agent（检索 RAG 知识库）
3. 检索结果返回 → DP 调度 Agent → 用户对话 Agent（结合检索结果生成回复）
4. 合规校验 → DP 调度 Agent → 返回用户

#### 场景 3：生成周度情绪报告

1. 用户发起「生成周报告」指令 → DP 调度 Agent → 合规检查 → 语言分析 Agent（打任务标签）
2. DP 调度 Agent → 任务执行 Agent（创建报告生成任务）
3. 任务执行 Agent 拆解任务 → DP 调度 Agent → 检索 Agent（拉取近 7 天对话标签数据）
4. 数据返回 → DP 调度 Agent → 用户画像与报告 Agent（生成报告）
5. 报告生成完成 → DP 调度 Agent → 合规检查 Agent（报告内容校验）
6. 校验通过 → DP 调度 Agent → 任务执行 Agent（标记任务完成）
7. DP 调度 Agent → 用户对话 Agent（通知用户报告完成）→ 返回用户