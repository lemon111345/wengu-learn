-- #90：带源码的个人经历题参与调度（决策 051）。给已经确认的这类知识点补上调度状态，以后的在确认时就会初始化。
-- 初始值和 review.fsrs.Card.newCard 相同：LEARNING、第 0 步、到期时间为现在、没有复习过。
-- 例外：这里直接写 review 模块的 card_state 表，越过了“模块之间只调 Service”的规则。一次性补数据，见决策 051。

INSERT INTO card_state (kp_id, user_id, state, step, stability, difficulty, due, last_review)
SELECT k.id, k.user_id, 'LEARNING', 0, NULL, NULL, UTC_TIMESTAMP(3), NULL
FROM knowledge_point k
         JOIN kp_code c ON c.kp_id = k.id
WHERE k.type = 'EXPERIENCE'
  AND NOT EXISTS (SELECT 1 FROM card_state s WHERE s.kp_id = k.id);
