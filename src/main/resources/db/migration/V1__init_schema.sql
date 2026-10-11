-- =============================================================
-- self-mirror-agent 初始表结构（docs/data_model.md §4.2）
-- MySQL 8.0 / utf8mb4
-- =============================================================

-- 4.2.1 用户表
CREATE TABLE IF NOT EXISTS sys_user (
    id          BIGINT       NOT NULL COMMENT '用户ID，雪花算法生成',
    username    VARCHAR(64)  NOT NULL COMMENT '登录用户名',
    nickname    VARCHAR(64)  DEFAULT NULL COMMENT '用户昵称',
    password    VARCHAR(128) NOT NULL COMMENT '加密密码',
    status      TINYINT      NOT NULL DEFAULT 0 COMMENT '账号状态：0正常 1禁用',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    is_deleted  TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记',
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '用户表';

-- 4.2.2 会话表
CREATE TABLE IF NOT EXISTS conversation (
    id               BIGINT       NOT NULL COMMENT '会话ID',
    user_id          BIGINT       NOT NULL COMMENT '关联用户ID',
    title            VARCHAR(255) DEFAULT NULL COMMENT '会话标题（自动生成）',
    start_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '会话开始时间',
    last_active_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最后活跃时间',
    status           TINYINT      NOT NULL DEFAULT 0 COMMENT '会话状态：0进行中 1已结束',
    message_count    INT          NOT NULL DEFAULT 0 COMMENT '消息总数',
    create_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_user_active (user_id, last_active_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '会话表';

-- 4.2.3 对话消息表
CREATE TABLE IF NOT EXISTS message (
    id              BIGINT      NOT NULL COMMENT '消息ID',
    conversation_id BIGINT      NOT NULL COMMENT '关联会话ID',
    user_id         BIGINT      NOT NULL COMMENT '关联用户ID',
    role            TINYINT     NOT NULL COMMENT '角色：0用户 1系统',
    content         TEXT        NOT NULL COMMENT '消息内容原文',
    vector_id       VARCHAR(64) DEFAULT NULL COMMENT '关联Milvus向量ID',
    create_time     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '消息发送时间',
    is_deleted      TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除标记',
    PRIMARY KEY (id),
    KEY idx_conversation (conversation_id, create_time),
    KEY idx_user_time (user_id, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '对话消息表';

-- 4.2.4 标签记录表
CREATE TABLE IF NOT EXISTS tag_record (
    id           BIGINT       NOT NULL COMMENT '标签记录ID',
    message_id   BIGINT       NOT NULL COMMENT '关联消息ID',
    user_id      BIGINT       NOT NULL COMMENT '关联用户ID',
    tag_category VARCHAR(32)  NOT NULL COMMENT '标签大类：emotion/intent/psychology/task',
    tag_code     VARCHAR(64)  NOT NULL COMMENT '标签编码',
    tag_name     VARCHAR(64)  NOT NULL COMMENT '标签名称',
    confidence   DECIMAL(3,2) NOT NULL DEFAULT 0.00 COMMENT '置信度 0-1',
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_message (message_id),
    KEY idx_user_category_time (user_id, tag_category, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '标签记录表';

-- 4.2.5 任务表
CREATE TABLE IF NOT EXISTS task (
    id              BIGINT       NOT NULL COMMENT '任务ID',
    user_id         BIGINT       NOT NULL COMMENT '关联用户ID',
    task_type       VARCHAR(32)  NOT NULL COMMENT '任务类型：report/statistic/profile_update',
    task_name       VARCHAR(128) NOT NULL COMMENT '任务名称',
    task_params     JSON         DEFAULT NULL COMMENT '任务参数（JSON格式）',
    status          TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0待执行 1执行中 2已完成 3失败 4已取消',
    cron_expression VARCHAR(64)  DEFAULT NULL COMMENT '周期任务Cron表达式',
    progress        INT          NOT NULL DEFAULT 0 COMMENT '任务进度 0-100',
    result_id       BIGINT       DEFAULT NULL COMMENT '关联结果ID（如报告ID）',
    error_msg       VARCHAR(512) DEFAULT NULL COMMENT '失败错误信息',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_user_status (user_id, status),
    KEY idx_type_status (task_type, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '任务表';

-- 4.2.6 子任务表
CREATE TABLE IF NOT EXISTS sub_task (
    id          BIGINT      NOT NULL COMMENT '子任务ID',
    task_id     BIGINT      NOT NULL COMMENT '关联主任务ID',
    step_order  INT         NOT NULL COMMENT '执行顺序',
    step_name   VARCHAR(64) NOT NULL COMMENT '步骤名称',
    status      TINYINT     NOT NULL DEFAULT 0 COMMENT '状态：0待执行 1执行中 2已完成 3失败',
    retry_count INT         NOT NULL DEFAULT 0 COMMENT '已重试次数',
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_task_order (task_id, step_order)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '子任务表';

-- 4.2.7 用户画像表
CREATE TABLE IF NOT EXISTS user_profile (
    id                BIGINT   NOT NULL COMMENT '画像ID',
    user_id           BIGINT   NOT NULL COMMENT '关联用户ID',
    version           INT      NOT NULL DEFAULT 1 COMMENT '画像版本号',
    stat_date         DATE     NOT NULL COMMENT '统计截止日期',
    emotion_feature   JSON     DEFAULT NULL COMMENT '情绪特征数据',
    psychology_feature JSON    DEFAULT NULL COMMENT '心理特征数据',
    behavior_feature  JSON     DEFAULT NULL COMMENT '行为模式数据',
    sample_count      INT      NOT NULL DEFAULT 0 COMMENT '样本消息数量',
    create_time       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_version (user_id, version),
    KEY idx_user_date (user_id, stat_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '用户画像表';

-- 4.2.8 分析报告表
CREATE TABLE IF NOT EXISTS analysis_report (
    id           BIGINT       NOT NULL COMMENT '报告ID',
    user_id      BIGINT       NOT NULL COMMENT '关联用户ID',
    report_type  VARCHAR(32)  NOT NULL COMMENT '类型：daily/weekly/monthly/custom',
    report_title VARCHAR(255) NOT NULL COMMENT '报告标题',
    start_date   DATE         NOT NULL COMMENT '统计开始日期',
    end_date     DATE         NOT NULL COMMENT '统计结束日期',
    summary      TEXT         DEFAULT NULL COMMENT '报告核心摘要',
    content      LONGTEXT     DEFAULT NULL COMMENT '报告完整内容（Markdown）',
    stat_data    JSON         DEFAULT NULL COMMENT '统计指标数据（供图表渲染）',
    task_id      BIGINT       DEFAULT NULL COMMENT '关联任务ID',
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    is_deleted   TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记',
    PRIMARY KEY (id),
    KEY idx_user_time (user_id, create_time),
    KEY idx_task (task_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '分析报告表';

-- 4.2.9 RAG 知识库表
CREATE TABLE IF NOT EXISTS knowledge_doc (
    id          BIGINT       NOT NULL COMMENT '文档ID',
    title       VARCHAR(255) NOT NULL COMMENT '文档标题',
    category    VARCHAR(64)  NOT NULL COMMENT '分类：情绪调节/心理科普/自我成长',
    content     TEXT         NOT NULL COMMENT '文档原文',
    chunk_count INT          NOT NULL DEFAULT 0 COMMENT '分块数量',
    status      TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0未向量化 1已向量化 2已禁用',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_category_status (category, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'RAG知识库表';
