-- 事件链路 v2 补充：现状摘要归并时判定「生活场景分类」（19 类固定枚举，见 SceneCategory）
-- 建档 bootstrap 不判定，保持 NULL；封稿/定稿后刷新时写入
ALTER TABLE event
    ADD COLUMN scene_category VARCHAR(64) DEFAULT NULL COMMENT '场景分类（19 类枚举中文标签之一，摘要归并时判定；未定稿为 NULL）' AFTER summary;
