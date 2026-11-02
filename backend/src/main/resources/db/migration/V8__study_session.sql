-- #19：学习会话、流式对话、自动计时。设计见决策 018、025。

ALTER TABLE study_session
    ADD COLUMN type VARCHAR(16) NOT NULL DEFAULT 'STUDY'
        COMMENT 'STUDY 学习知识点 / FOLLOW_UP 接着问 / QUESTION 从一个问题开始（决策 018）' AFTER user_id,
    MODIFY COLUMN kp_id BIGINT NULL COMMENT '学习的知识点；从一个问题开始时为空',
    ADD COLUMN skipped_explanation BOOLEAN NOT NULL DEFAULT FALSE
        COMMENT '是否点了“这个我会，直接完整作答”，跳过了讲解' AFTER ended_at,
    ADD COLUMN active_seconds INT NOT NULL DEFAULT 0
        COMMENT '有效时长（秒）：两次活动之间的间隔最多算 10 分钟（决策 025）' AFTER skipped_explanation,
    ADD COLUMN last_active_at DATETIME(3) NULL COMMENT '上次活动的时间，计算有效时长用' AFTER active_seconds,
    ADD COLUMN prompt_version VARCHAR(32) NULL COMMENT '学习对话用的 prompt 版本，以后分析效果用' AFTER last_active_at;

ALTER TABLE message
    ADD COLUMN marker VARCHAR(16) NULL
        COMMENT '老师回复中的标记：RESTATE 要求完整作答 / END 学习结束；标记本身不存进 content' AFTER content,
    ADD COLUMN model_note VARCHAR(255) NULL
        COMMENT '附在学生消息后面、只给模型看的提示，如“只剩最后一轮”；重建发给模型的历史时原样带上，前端不显示'
        AFTER marker;
