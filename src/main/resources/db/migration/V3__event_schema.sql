-- =============================================================
-- 情绪-事件关联：事件表与事件提及表（doc/modify/情绪事件关联 20261015）
-- 事件按 user_id 归属（与画像/标签/检索同维度）；mention 冗余 conversation_id 仅作溯源
-- =============================================================

CREATE TABLE IF NOT EXISTS event (
    id                 BIGINT       NOT NULL COMMENT '事件ID，雪花算法生成',
    user_id            BIGINT       NOT NULL COMMENT '归属用户ID（事件为用户级数据）',
    name               VARCHAR(64)  NOT NULL COMMENT '事件名称（≤15字短语）',
    summary            VARCHAR(512) NOT NULL COMMENT '事件摘要（≤200字，渐进改写为现状摘要）',
    status             TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0 EMERGING(增量叙述中) 1 SETTLED(已定稿)',
    core_facts         VARCHAR(512) DEFAULT NULL COMMENT '核心要素JSON：时段/参与者/主题（同一性判定依据）',
    emotion_profile    VARCHAR(256) DEFAULT NULL COMMENT '最近提及携带的情绪码JSON数组（冗余，明细在event_mention）',
    mention_count      INT          NOT NULL DEFAULT 0 COMMENT '提及总次数',
    rekindle_count     INT          NOT NULL DEFAULT 0 COMMENT '重提次数（episode语义，连续提及只算1次）',
    first_mentioned_at DATETIME     DEFAULT NULL COMMENT '首次提及时间',
    last_mentioned_at  DATETIME     DEFAULT NULL COMMENT '最近提及时间',
    last_active_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近活跃时间（rerank时间衰减用）',
    create_time        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    is_deleted         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记',
    PRIMARY KEY (id),
    KEY idx_user_active (user_id, last_active_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '人生事件表（情绪-事件关联）';

CREATE TABLE IF NOT EXISTS event_mention (
    id              BIGINT       NOT NULL COMMENT '提及记录ID',
    event_id        BIGINT       NOT NULL COMMENT '关联事件ID',
    user_id         BIGINT       NOT NULL COMMENT '归属用户ID（统计免联表）',
    message_id      BIGINT       NOT NULL COMMENT '触发提及的用户消息ID（溯源）',
    conversation_id BIGINT       NOT NULL COMMENT '所属会话ID（仅溯源，不参与逻辑）',
    mention_type    TINYINT      NOT NULL COMMENT '类型：0 NEW(首次立项) 1 CONTINUATION(同话题连续) 2 REKINDLED(重提)',
    emotion_codes   VARCHAR(256) DEFAULT NULL COMMENT '本次提及携带的情绪码JSON数组（可为空=无情绪事件）',
    psychology_codes VARCHAR(256) DEFAULT NULL COMMENT '本次提及携带的心理特征码JSON数组',
    confidence      DOUBLE       NOT NULL DEFAULT 0 COMMENT '事件归因置信度',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '提及时间（重提时间点统计源）',
    PRIMARY KEY (id),
    KEY idx_event_time (event_id, create_time),
    KEY idx_user_time (user_id, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '事件提及明细（情绪×事件×时间点，统计与溯源依据）';
