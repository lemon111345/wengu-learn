-- #90：源码抽出的草稿条目特有的数据。确认时被接受的复制到 kp_code（下一个迁移）。设计见决策 050。

CREATE TABLE extraction_draft_item_code (
    item_id       BIGINT NOT NULL COMMENT 'extraction_draft_item.id，一个条目最多一行',
    user_id       BIGINT NOT NULL,
    details       TEXT   NULL COMMENT '细节，0～3 条，漏了不降档；没有时为空',
    evidence      TEXT   NOT NULL COMMENT '代码依据 JSON：[{file, symbol}]',
    unverifiable  TEXT   NOT NULL COMMENT '代码证明不了的问题 JSON：["…"]',
    prerequisites TEXT   NOT NULL COMMENT '前置概念 JSON：[{concept, explanation}]',
    PRIMARY KEY (item_id)
) COMMENT '源码抽出的草稿条目特有的数据';
