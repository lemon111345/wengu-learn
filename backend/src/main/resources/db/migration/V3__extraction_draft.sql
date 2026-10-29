-- #15：抽取草稿。一次抽取的结果先存成草稿，用户确认后（#17）才生成知识点。设计见决策 012。

CREATE TABLE extraction_draft (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    user_id           BIGINT      NOT NULL,
    source_id         BIGINT      NULL COMMENT '来源材料；由学习会话产生的草稿没有 source，届时由 #31 加 session_id',
    status            VARCHAR(16) NOT NULL COMMENT 'READY（待确认）/ FAILED',
    questions         LONGTEXT    NOT NULL COMMENT '这次发给模型的题目 JSON：[{id, text}]，id 为 source 中的题号',
    raw_output        LONGTEXT    NULL COMMENT '模型最后一次的原始输出，为 #9 保留真实数据',
    error             TEXT        NULL COMMENT '失败原因：校验发现的问题或异常信息。只用于排查，不返回给前端',
    attempts          INT         NOT NULL COMMENT '调用了几次模型，1 或 2',
    prompt_version    VARCHAR(16) NOT NULL,
    model             VARCHAR(64) NULL COMMENT '模型调用直接失败时为空',
    prompt_tokens     INT         NULL COMMENT '各次调用累加',
    completion_tokens INT         NULL COMMENT '各次调用累加',
    created_at        DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_extraction_draft_user (user_id),
    KEY idx_extraction_draft_source (source_id)
) COMMENT '知识点抽取草稿';

CREATE TABLE extraction_draft_item (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    user_id             BIGINT       NOT NULL,
    draft_id            BIGINT       NOT NULL,
    item_order          INT          NOT NULL COMMENT '模型给出的学习顺序，从 1 开始',
    title               VARCHAR(500) NOT NULL,
    summary             TEXT         NOT NULL,
    type                VARCHAR(32)  NOT NULL COMMENT 'KnowledgePointType 的名称',
    source_question_ids VARCHAR(255) NOT NULL COMMENT '来源题号，如 "1,3,5"，对应 source 中的题号',
    PRIMARY KEY (id),
    KEY idx_extraction_draft_item_draft (draft_id)
) COMMENT '草稿中的一个知识点';
