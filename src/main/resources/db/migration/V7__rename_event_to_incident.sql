-- =============================================================
-- 语义重构「事件 → 事由（Incident）」：存量数据全保留，仅更名。
-- 事由定义：用户提及的具体事情，重点是情绪的原因（广义，含无情绪场景的历史存量）。
-- Milvus 存量向量不迁移（新 collection 由应用启动自动按新 schema 建立，旧集合建议手动 drop）；
-- Redis 旧 key mirror:event:finalize 不自动清理（残留 member 随下一轮提及重新入队）。
-- =============================================================

RENAME TABLE event TO incident,
             event_mention TO incident_mention,
             event_relation TO incident_relation;

ALTER TABLE incident
    COMMENT '事由表（情绪-事由关联，原事件表；用户级数据）',
    MODIFY name VARCHAR(64) NOT NULL COMMENT '事由名称（≤15字短语）',
    MODIFY summary VARCHAR(512) NOT NULL COMMENT '事由现状摘要（事实归并的派生缓存，可重建）',
    MODIFY emotion_profile VARCHAR(256) DEFAULT NULL
        COMMENT '最近提及携带的情绪码JSON数组（冗余，明细在 incident_mention）';

ALTER TABLE incident_mention
    CHANGE event_id incident_id BIGINT NOT NULL COMMENT '关联事由ID',
    MODIFY confidence DOUBLE NOT NULL DEFAULT 0 COMMENT '事由归因置信度',
    COMMENT '事由提及明细（情绪×事由×时间点，统计与溯源依据）';
ALTER TABLE incident_mention RENAME INDEX idx_event_time TO idx_incident_time;

ALTER TABLE incident_relation
    CHANGE event_id incident_id BIGINT NOT NULL COMMENT '主动引用方事由ID',
    CHANGE ref_event_id ref_incident_id BIGINT NOT NULL COMMENT '被引用的旧事由ID',
    COMMENT '事由间引用关系边（对比/因果/提及，非同一事由）';
ALTER TABLE incident_relation RENAME INDEX idx_rel_event TO idx_rel_incident;
ALTER TABLE incident_relation RENAME INDEX idx_rel_ref TO idx_rel_ref_incident;

ALTER TABLE tag_record
    CHANGE event_id incident_id BIGINT DEFAULT NULL COMMENT '关联事由ID（NULL=未归因事由）',
    COMMENT '标签记录表（情绪/意图/心理/任务打标明细，事由归因）';
ALTER TABLE tag_record RENAME INDEX idx_tag_event TO idx_tag_incident;
