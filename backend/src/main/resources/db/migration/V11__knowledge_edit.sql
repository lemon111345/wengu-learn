-- #25：知识点的删除、合并、编辑。设计见决策 029。

ALTER TABLE knowledge_point
    ADD COLUMN deleted_at DATETIME(3) NULL
        COMMENT '软删除的时间，为空表示没删；撤销删除时清空。不用 status 表示：暂停的知识点删除后撤销，要回到暂停'
        AFTER draft_item_id,
    ADD COLUMN merged_into BIGINT NULL
        COMMENT '被合并时指向主知识点，同时写 deleted_at；学习记录仍留在原知识点上'
        AFTER deleted_at;

CREATE TABLE kp_revision (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    user_id    BIGINT       NOT NULL,
    kp_id      BIGINT       NOT NULL,
    type       VARCHAR(32)  NOT NULL COMMENT '修改前的类型',
    title      VARCHAR(500) NOT NULL COMMENT '修改前的标题',
    summary    TEXT         NOT NULL COMMENT '修改前的要点',
    created_at DATETIME(3)  NOT NULL COMMENT '修改的时间',
    PRIMARY KEY (id),
    KEY idx_kp_revision_kp (kp_id)
) COMMENT '知识点每次修改前的快照（#9 的数据）：AI 的原文在草稿条目中，加上这些快照，每一版都能拼出来';
