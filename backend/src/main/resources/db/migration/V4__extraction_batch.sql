-- #16：异步抽取、分批。一份草稿是一次抽取，下面分成若干批，每批单独调用模型。设计见决策 013。
-- 原来记在草稿上的调用信息（状态、题目、原始输出、失败原因、token 等）搬到批次上；草稿的状态由批次推算，不再存。

CREATE TABLE extraction_batch (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    user_id           BIGINT      NOT NULL,
    draft_id          BIGINT      NOT NULL,
    batch_no          INT         NOT NULL COMMENT '第几批，从 1 开始。重试时新增一行、批号不变，旧行保留，为 #9 保留失败数据',
    status            VARCHAR(16) NOT NULL COMMENT 'PENDING（排队中）/ RUNNING / READY / FAILED',
    questions         LONGTEXT    NOT NULL COMMENT '这一批发给模型的题目 JSON：[{id, text}]，id 为 source 中的题号',
    raw_output        LONGTEXT    NULL COMMENT '模型最后一次的原始输出',
    error             TEXT        NULL COMMENT '失败原因：校验发现的问题、异常信息或任务中断。只用于排查，不返回给前端',
    attempts          INT         NOT NULL COMMENT '调用了几次模型：排队中为 0，之后为 1 或 2',
    prompt_version    VARCHAR(16) NULL COMMENT '开始执行时写入',
    model             VARCHAR(64) NULL COMMENT '模型调用直接失败时为空',
    prompt_tokens     INT         NULL COMMENT '各次调用累加',
    completion_tokens INT         NULL COMMENT '各次调用累加',
    created_at        DATETIME(3) NOT NULL COMMENT '进入队列的时间',
    started_at        DATETIME(3) NULL,
    finished_at       DATETIME(3) NULL,
    PRIMARY KEY (id),
    KEY idx_extraction_batch_draft (draft_id),
    KEY idx_extraction_batch_status (status)
) COMMENT '知识点抽取的一批';

-- 已有的草稿都只调用过一次抽取，各自变成第 1 批
INSERT INTO extraction_batch (user_id, draft_id, batch_no, status, questions, raw_output, error, attempts,
                              prompt_version, model, prompt_tokens, completion_tokens,
                              created_at, started_at, finished_at)
SELECT user_id, id, 1, status, questions, raw_output, error, attempts,
       prompt_version, model, prompt_tokens, completion_tokens,
       created_at, created_at, created_at
FROM extraction_draft;

ALTER TABLE extraction_draft_item
    ADD COLUMN batch_id BIGINT NULL COMMENT '来自哪一批' AFTER draft_id;

UPDATE extraction_draft_item i
    JOIN extraction_batch b ON b.draft_id = i.draft_id
SET i.batch_id = b.id;

ALTER TABLE extraction_draft_item
    MODIFY COLUMN batch_id BIGINT NOT NULL COMMENT '来自哪一批',
    ADD KEY idx_extraction_draft_item_batch (batch_id);

ALTER TABLE extraction_draft
    DROP COLUMN status,
    DROP COLUMN questions,
    DROP COLUMN raw_output,
    DROP COLUMN error,
    DROP COLUMN attempts,
    DROP COLUMN prompt_version,
    DROP COLUMN model,
    DROP COLUMN prompt_tokens,
    DROP COLUMN completion_tokens;
