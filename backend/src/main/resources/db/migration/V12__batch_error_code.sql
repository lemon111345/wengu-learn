-- #27：抽取批次失败时，给用户看的原因。设计见决策 032。

ALTER TABLE extraction_batch
    ADD COLUMN error_code VARCHAR(32) NULL
        COMMENT '失败原因的返回码（ErrorCode 的名称），如 LLM_AUTH_FAILED、LLM_INVALID_OUTPUT，用来给出用户能看懂的提示；error 仍记原始信息，不返回前端。之前失败的批次为空'
        AFTER error;
