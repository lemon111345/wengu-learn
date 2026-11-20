-- #31：评分页勾选学习缺口加入知识库（决策 053）。一次评分建议的缺口只处理一次，处理之前两列都为空。
-- JSON 和 V10 的其他 JSON 列一样存成 TEXT。

ALTER TABLE rating_suggestion
    ADD COLUMN gap_picks    TEXT   NULL COMMENT '勾选加入知识库的缺口是 out_of_scope_gaps 的第几条，JSON：[0, 2]，从小到大；还没处理时为空',
    ADD COLUMN gap_draft_id BIGINT NULL COMMENT '由勾选的缺口生成的草稿（knowledge 的 extraction_draft），前端用它查看整理进度';
