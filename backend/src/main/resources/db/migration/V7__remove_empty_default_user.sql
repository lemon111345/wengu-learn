-- #46：只保留多用户模式（决策 017）。V1 插入的默认用户（id 1、用户名 default、没有密码）登录不上，
-- 没有业务数据时删掉它和它的设置，用户名 default 也空出来。
-- 有业务数据时不动：数据归谁不能由迁移脚本替用户决定，作者本地的库用 tools/migrate-default-user.sql 手动处理。
-- “业务数据”指下面各业务表中 user_id = 1 的行，不包括 user_settings。

SET @remove_default_user =
    EXISTS (SELECT 1 FROM `user` WHERE id = 1 AND username = 'default' AND password_hash IS NULL)
    AND NOT EXISTS (SELECT 1 FROM source WHERE user_id = 1)
    AND NOT EXISTS (SELECT 1 FROM knowledge_point WHERE user_id = 1)
    AND NOT EXISTS (SELECT 1 FROM kp_source WHERE user_id = 1)
    AND NOT EXISTS (SELECT 1 FROM card_state WHERE user_id = 1)
    AND NOT EXISTS (SELECT 1 FROM study_session WHERE user_id = 1)
    AND NOT EXISTS (SELECT 1 FROM message WHERE user_id = 1)
    AND NOT EXISTS (SELECT 1 FROM review_log WHERE user_id = 1)
    AND NOT EXISTS (SELECT 1 FROM extraction_draft WHERE user_id = 1)
    AND NOT EXISTS (SELECT 1 FROM extraction_batch WHERE user_id = 1)
    AND NOT EXISTS (SELECT 1 FROM extraction_draft_item WHERE user_id = 1);

DELETE FROM user_settings WHERE user_id = 1 AND @remove_default_user;
DELETE FROM `user` WHERE id = 1 AND @remove_default_user;
