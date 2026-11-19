-- #90：源码导入的文件快照。设计见决策 050。

CREATE TABLE source_file (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    user_id    BIGINT       NOT NULL,
    source_id  BIGINT       NOT NULL,
    path       VARCHAR(500) NOT NULL COMMENT '相对项目文件夹的路径，分隔符统一为 /',
    content    MEDIUMTEXT   NOT NULL COMMENT '文件内容，换行统一为 \n；导入后不再修改',
    chars      INT          NOT NULL COMMENT 'content 的字符数，计入 15 万字符的上限',
    file_order INT          NOT NULL COMMENT '导入时的顺序，从 1 开始',
    PRIMARY KEY (id),
    UNIQUE KEY uk_source_file_path (source_id, path)
) COMMENT '源码导入的文件快照';
