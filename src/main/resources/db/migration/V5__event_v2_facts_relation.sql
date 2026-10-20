-- =============================================================
-- 事件链路 v2（doc 讨论方案 20261020）：
-- ① event_mention 承载「增量事实层」——fact_content 只追加不改写，现状摘要封稿时由 facts 一次性生成；
--    ambiguous 标记 merge-bias 下拿不准先归入的提及行，供封稿复核与拆分通道。
-- ② event_relation 事件间引用关系边表（comparison/causal/mention）；
--    同一事件的延续/重提走 event_mention（不在此表），避免两套归因路径。
-- =============================================================

ALTER TABLE event_mention
    ADD COLUMN fact_content VARCHAR(512) DEFAULT NULL
        COMMENT '本条提及的增量事实JSON数组（只追加不改写，摘要于封稿时由全部facts生成）' AFTER psychology_codes,
    ADD COLUMN ambiguous TINYINT NOT NULL DEFAULT 0
        COMMENT '归入是否存疑：1=AMBIGUOUS先归入该事件（封稿复核，不属于则拆出）' AFTER fact_content;

CREATE TABLE IF NOT EXISTS event_relation (
    id                  BIGINT       NOT NULL COMMENT '关系ID，雪花算法生成',
    event_id            BIGINT       NOT NULL COMMENT '主动引用方事件ID',
    ref_event_id        BIGINT       NOT NULL COMMENT '被引用的旧事件ID',
    user_id             BIGINT       NOT NULL COMMENT '归属用户ID（与事件同维度隔离）',
    ref_type            VARCHAR(16)  NOT NULL COMMENT '引用类型：comparison对比/causal因果/mention单纯提及',
    ref_text            VARCHAR(128) DEFAULT NULL COMMENT '引用原文片段（如「比上次好」，可溯源）',
    evidence_message_id BIGINT       DEFAULT NULL COMMENT '建立该引用关系的触发消息ID（溯源）',
    create_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_relation (event_id, ref_event_id, ref_type),
    KEY idx_rel_event (event_id),
    KEY idx_rel_ref (ref_event_id),
    KEY idx_rel_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '事件间引用关系边（新旧事件对比/因果/提及，非同一事件）';
