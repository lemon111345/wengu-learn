-- #84：评分可以修改。评分时存下评分前的记忆状态，改分时恢复它、按新评分重算（决策 039）。
-- 连同已有的 state_before 正好是一张完整的卡片。
-- 判断有没有快照看 due_before：card_state.due 不为空，而新卡的 stability、difficulty、last_review 本来就是空的。

ALTER TABLE review_log
    ADD COLUMN step_before        INT         NULL COMMENT '评分前的学习步或重学步；REVIEW 时为空' AFTER state_before,
    ADD COLUMN stability_before   DOUBLE      NULL COMMENT '评分前的稳定性；新知识为空' AFTER step_before,
    ADD COLUMN difficulty_before  DOUBLE      NULL COMMENT '评分前的难度；新知识为空' AFTER stability_before,
    ADD COLUMN due_before         DATETIME(3) NULL COMMENT '评分前的到期时间；为空表示 V13 之前的记录，没有快照，不能改分' AFTER difficulty_before,
    ADD COLUMN last_review_before DATETIME(3) NULL COMMENT '评分前的上次复习时间；新知识为空' AFTER due_before;
