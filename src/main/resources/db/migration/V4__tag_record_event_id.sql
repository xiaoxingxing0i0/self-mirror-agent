-- =============================================================
-- tag_record 增加事件关联维度（情绪-事件归因，doc/modify 20261015 追加变更）
-- event_id 由事件链路异步回填：本轮按情绪码回填；历史情绪消息按模型回溯归因回填
-- =============================================================

ALTER TABLE tag_record
    ADD COLUMN event_id BIGINT DEFAULT NULL COMMENT '关联事件ID（NULL=未归因事件；日常闲聊不落此表）' AFTER confidence;

CREATE INDEX idx_tag_event ON tag_record (event_id);
