-- #17：确认草稿。勾选的条目生成知识点，并初始化调度状态；取消的条目也记下来。设计见决策 015。

ALTER TABLE knowledge_point
    ADD COLUMN draft_item_id BIGINT NULL COMMENT '来自哪个草稿条目，用于逐条对比 AI 输出和最终结果（#9）；合并或手动新建的知识点为空'
        AFTER sort_order,
    ADD KEY idx_kp_draft_item (draft_item_id);

ALTER TABLE extraction_draft
    ADD COLUMN confirmed_at DATETIME(3) NULL COMMENT '确认时间，为空表示还没确认。草稿的其他状态由批次推算（决策 013）'
        AFTER created_at;

ALTER TABLE extraction_draft_item
    ADD COLUMN decision VARCHAR(16) NULL COMMENT 'ACCEPTED（生成了知识点）/ REJECTED（用户取消勾选）；为空表示草稿还没确认';
