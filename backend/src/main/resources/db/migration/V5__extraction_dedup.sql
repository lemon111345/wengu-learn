-- #16：抽取的去重上下文。每批执行时把已有知识点和同一草稿中已成功批次的条目传给模型，设计见决策 014。

ALTER TABLE extraction_batch
    ADD COLUMN context LONGTEXT NULL COMMENT '这一批实际用的去重上下文 JSON：[{label, knowledgePointId, draftItemId, title}]；失败时也保存，为 #9 保留数据'
        AFTER questions,
    ADD COLUMN covered LONGTEXT NULL COMMENT '被已有内容覆盖的题 JSON：[{questionId, knowledgePointIds, draftItemIds, titles, reason}]，titles 是抽取当时的标题快照'
        AFTER context;
