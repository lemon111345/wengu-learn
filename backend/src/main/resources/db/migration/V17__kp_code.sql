-- #90：源码知识点特有的数据，确认源码草稿时从 extraction_draft_item_code 复制过来。设计见决策 050。

CREATE TABLE kp_code (
    kp_id         BIGINT NOT NULL COMMENT 'knowledge_point.id，一个知识点最多一行；题库抽出的个人经历题没有这一行',
    user_id       BIGINT NOT NULL,
    source_id     BIGINT NOT NULL COMMENT '来自哪次源码导入，学习时按它和依据的路径取文件快照（source_file）',
    details       TEXT   NULL COMMENT '细节，0～3 条，漏了不降档；没有时为空',
    evidence      TEXT   NOT NULL COMMENT '代码依据 JSON：[{file, symbol}]',
    unverifiable  TEXT   NOT NULL COMMENT '代码证明不了的问题 JSON：["…"]',
    prerequisites TEXT   NOT NULL COMMENT '前置概念 JSON：[{concept, explanation}]',
    PRIMARY KEY (kp_id)
) COMMENT '源码知识点特有的数据';
