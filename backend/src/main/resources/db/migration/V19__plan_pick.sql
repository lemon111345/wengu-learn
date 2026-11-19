-- #128：用户自己加入今天的知识点（决策 052）。只记用户的意图，计划仍然每次实时计算。
-- 只在 study_date 那个学习日算“今天加入的”；同一天重复加入由唯一索引挡住，它也覆盖按用户和学习日的查询。

CREATE TABLE plan_pick (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    user_id    BIGINT      NOT NULL,
    kp_id      BIGINT      NOT NULL,
    study_date DATE        NOT NULL COMMENT '加入的是哪个学习日，按用户的时区和 day_start_hour 划分',
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pick_user_date_kp (user_id, study_date, kp_id)
) COMMENT '用户自己加入今天的知识点：只在加入的那个学习日有效，是用户的意图，不是计划的快照（决策 052）';
