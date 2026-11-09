-- #53：老师的称呼，只改变显示名称和模型的自称。设计见决策 042。

ALTER TABLE user_settings
    ADD COLUMN teacher_name VARCHAR(20) NOT NULL DEFAULT '老师' COMMENT '老师的称呼，最多 10 个字（决策 042）';
