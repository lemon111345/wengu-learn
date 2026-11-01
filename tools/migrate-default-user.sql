-- 一次性脚本：把 V1 默认用户（id 1）名下的数据转给一个新注册的账号，然后删掉默认用户（决策 017 第 8 条）。
-- 只用于只保留多用户模式（#46）之前就有数据的库，如作者本地的开发库；不是迁移脚本，不会自动执行。
--
-- 用法（在仓库根目录，MySQL 用 compose.yaml 启动）：
--   1. 备份：docker compose exec mysql mysqldump -uwengu -pwengu wengu > backup.sql
--   2. 启动新版后端，在页面或接口中注册自己的账号
--   3. 把下面的 @new_username 改成刚注册的用户名，然后执行：
--      docker compose exec -T mysql mysql -uwengu -pwengu --default-character-set=utf8mb4 wengu < tools/migrate-default-user.sql
--   4. 看最后输出的表：“默认用户”一列应当全为 0，“新账号”一列是转过来的行数
--
-- 用户名写错（查不到这个账号）时什么都不改，最后的表中第一行“新账号 id”为 NULL。

SET @new_username = '改成你的用户名';
SET @new_id = (SELECT id FROM `user` WHERE username = @new_username AND id <> 1);

START TRANSACTION;

-- 各业务表和 V7 中列出的一致
UPDATE source SET user_id = @new_id WHERE user_id = 1 AND @new_id IS NOT NULL;
UPDATE knowledge_point SET user_id = @new_id WHERE user_id = 1 AND @new_id IS NOT NULL;
UPDATE kp_source SET user_id = @new_id WHERE user_id = 1 AND @new_id IS NOT NULL;
UPDATE card_state SET user_id = @new_id WHERE user_id = 1 AND @new_id IS NOT NULL;
UPDATE study_session SET user_id = @new_id WHERE user_id = 1 AND @new_id IS NOT NULL;
UPDATE message SET user_id = @new_id WHERE user_id = 1 AND @new_id IS NOT NULL;
UPDATE review_log SET user_id = @new_id WHERE user_id = 1 AND @new_id IS NOT NULL;
UPDATE extraction_draft SET user_id = @new_id WHERE user_id = 1 AND @new_id IS NOT NULL;
UPDATE extraction_batch SET user_id = @new_id WHERE user_id = 1 AND @new_id IS NOT NULL;
UPDATE extraction_draft_item SET user_id = @new_id WHERE user_id = 1 AND @new_id IS NOT NULL;

-- 新账号注册时已经有了自己的设置，默认用户的设置不要了
DELETE FROM user_settings WHERE user_id = 1 AND @new_id IS NOT NULL;
DELETE FROM `user` WHERE id = 1 AND username = 'default' AND password_hash IS NULL AND @new_id IS NOT NULL;

COMMIT;

SELECT '新账号 id' AS `表`, NULL AS `默认用户`, @new_id AS `新账号`
UNION ALL SELECT 'source', COUNT(CASE WHEN user_id = 1 THEN 1 END), COUNT(CASE WHEN user_id = @new_id THEN 1 END) FROM source
UNION ALL SELECT 'knowledge_point', COUNT(CASE WHEN user_id = 1 THEN 1 END), COUNT(CASE WHEN user_id = @new_id THEN 1 END) FROM knowledge_point
UNION ALL SELECT 'kp_source', COUNT(CASE WHEN user_id = 1 THEN 1 END), COUNT(CASE WHEN user_id = @new_id THEN 1 END) FROM kp_source
UNION ALL SELECT 'card_state', COUNT(CASE WHEN user_id = 1 THEN 1 END), COUNT(CASE WHEN user_id = @new_id THEN 1 END) FROM card_state
UNION ALL SELECT 'study_session', COUNT(CASE WHEN user_id = 1 THEN 1 END), COUNT(CASE WHEN user_id = @new_id THEN 1 END) FROM study_session
UNION ALL SELECT 'message', COUNT(CASE WHEN user_id = 1 THEN 1 END), COUNT(CASE WHEN user_id = @new_id THEN 1 END) FROM message
UNION ALL SELECT 'review_log', COUNT(CASE WHEN user_id = 1 THEN 1 END), COUNT(CASE WHEN user_id = @new_id THEN 1 END) FROM review_log
UNION ALL SELECT 'extraction_draft', COUNT(CASE WHEN user_id = 1 THEN 1 END), COUNT(CASE WHEN user_id = @new_id THEN 1 END) FROM extraction_draft
UNION ALL SELECT 'extraction_batch', COUNT(CASE WHEN user_id = 1 THEN 1 END), COUNT(CASE WHEN user_id = @new_id THEN 1 END) FROM extraction_batch
UNION ALL SELECT 'extraction_draft_item', COUNT(CASE WHEN user_id = 1 THEN 1 END), COUNT(CASE WHEN user_id = @new_id THEN 1 END) FROM extraction_draft_item
UNION ALL SELECT 'user（id 1 还在吗）', COUNT(*), NULL FROM `user` WHERE id = 1;
