-- #20：评分与调度、AI 评分建议、“当天完成”的记录。设计见决策 020、026。

ALTER TABLE review_log
    ADD COLUMN due_after DATETIME(3) NULL
        COMMENT '评分后的下次到期时间（决策 020）：知识库的学习记录、连续学习的推算要用；V10 之前的记录为空'
        AFTER is_first_review,
    -- 一次学习只评一次分：代码中先检查，这里兜底。session_id 为空的记录（以后其他来源）不受限制
    ADD UNIQUE KEY uk_review_session (session_id);

CREATE TABLE rating_suggestion (
    session_id        BIGINT      NOT NULL COMMENT '学习会话，一个会话只有一条建议',
    user_id           BIGINT      NOT NULL,
    rating            VARCHAR(8)  NOT NULL COMMENT '建议的评分：AGAIN / HARD / GOOD / EASY',
    analysis          TEXT        NOT NULL COMMENT '判断依据',
    weak_points       TEXT        NOT NULL COMMENT '要点范围内还没掌握的内容 JSON：["…"]，复习时针对提问（#21）',
    out_of_scope_gaps TEXT        NOT NULL COMMENT '超出要点范围、学生明确不会的内容 JSON：["…？"]，学完后作为候选知识点（#31）',
    model             VARCHAR(64) NULL,
    prompt_version    VARCHAR(16) NOT NULL,
    prompt_tokens     INT         NULL,
    completion_tokens INT         NULL,
    created_at        DATETIME(3) NOT NULL,
    PRIMARY KEY (session_id),
    KEY idx_rating_suggestion_user (user_id)
) COMMENT 'AI 评分建议（决策 008、026）';

CREATE TABLE study_day_completion (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    user_id      BIGINT      NOT NULL,
    study_date   DATE        NOT NULL COMMENT '学习日，按用户的时区和 day_start_hour 划分',
    completed_at DATETIME(3) NOT NULL COMMENT '发现计划变空的时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_completion_user_date (user_id, study_date)
) COMMENT '“当天完成”的记录：某个学习日的计划变空时写一条，是已经发生的事实，不是计划的快照（决策 020）';
