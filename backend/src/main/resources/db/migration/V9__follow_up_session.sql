-- #19：接着问。接着问是单独的会话，挂在原来的学习会话下面，每个学习会话最多一个。设计见决策 025。

ALTER TABLE study_session
    ADD COLUMN parent_session_id BIGINT NULL
        COMMENT '接着问的会话指向原来的学习会话；其他类型为空' AFTER kp_id,
    ADD KEY idx_session_parent (parent_session_id);
