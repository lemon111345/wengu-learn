-- M1 的初始表结构，字段含义见 docs/tech-design.md“数据模型”，约定见决策 009。
-- 时间一律存 UTC（JDBC 连接设为 connectionTimeZone=UTC）；枚举存名称；不建外键，由 Service 保证一致。
-- 已经执行过的迁移脚本不能再修改，改表结构要新建 V2、V3……

-- user 是 MySQL 的关键字（非保留），加反引号避免歧义
CREATE TABLE `user` (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(100) NULL COMMENT '单用户模式下为空',
    created_at    DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_username (username)
) COMMENT '用户';

CREATE TABLE user_settings (
    user_id         BIGINT      NOT NULL,
    daily_new_limit INT         NOT NULL COMMENT '每天新学几个知识点',
    timezone        VARCHAR(64) NOT NULL COMMENT 'IANA 时区，如 Asia/Shanghai',
    day_start_hour  TINYINT     NOT NULL COMMENT '一天从几点开始算，0～23',
    PRIMARY KEY (user_id)
) COMMENT '用户设置';

CREATE TABLE source (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    user_id    BIGINT       NOT NULL,
    type       VARCHAR(16)  NOT NULL COMMENT 'QUESTION / CODE / DOCUMENT / IMAGE',
    name       VARCHAR(255) NOT NULL,
    content    LONGTEXT     NOT NULL COMMENT '材料的原文',
    created_at DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_source_user (user_id)
) COMMENT '导入的学习材料';

CREATE TABLE knowledge_point (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    user_id    BIGINT       NOT NULL,
    type       VARCHAR(32)  NOT NULL COMMENT 'KnowledgePointType 的名称',
    title      VARCHAR(500) NOT NULL COMMENT '写成一个问题',
    summary    TEXT         NOT NULL,
    status     VARCHAR(16)  NOT NULL COMMENT 'ACTIVE / SUSPENDED',
    sort_order INT          NOT NULL COMMENT '新知识按 sort_order、id 派发，确认草稿时按抽取顺序写入',
    created_at DATETIME(3)  NOT NULL,
    updated_at DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_kp_user_sort (user_id, sort_order)
) COMMENT '知识点';

CREATE TABLE kp_source (
    kp_id     BIGINT NOT NULL,
    source_id BIGINT NOT NULL,
    user_id   BIGINT NOT NULL,
    PRIMARY KEY (kp_id, source_id),
    KEY idx_kp_source_source (source_id)
) COMMENT '知识点与材料的多对多关联';

CREATE TABLE card_state (
    kp_id       BIGINT      NOT NULL COMMENT '一个知识点只有一行调度状态',
    user_id     BIGINT      NOT NULL COMMENT '冗余，用于按用户查到期',
    state       VARCHAR(16) NOT NULL COMMENT 'LEARNING / REVIEW / RELEARNING，与 review.fsrs.State 一致',
    step        INT         NULL COMMENT '学习步或重学步的序号；REVIEW 时为空',
    stability   DOUBLE      NULL COMMENT '新知识为空',
    difficulty  DOUBLE      NULL COMMENT '新知识为空',
    due         DATETIME(3) NOT NULL,
    last_review DATETIME(3) NULL COMMENT '为空表示新知识',
    PRIMARY KEY (kp_id),
    KEY idx_card_user_due (user_id, due)
) COMMENT '调度状态';

CREATE TABLE study_session (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    user_id    BIGINT      NOT NULL,
    kp_id      BIGINT      NOT NULL,
    started_at DATETIME(3) NOT NULL,
    ended_at   DATETIME(3) NULL COMMENT '为空表示还没结束',
    PRIMARY KEY (id),
    KEY idx_session_user_started (user_id, started_at),
    KEY idx_session_kp (kp_id)
) COMMENT '学习会话，也是自动计时的来源';

CREATE TABLE message (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    user_id    BIGINT      NOT NULL,
    session_id BIGINT      NOT NULL,
    role       VARCHAR(16) NOT NULL COMMENT 'USER / ASSISTANT',
    content    TEXT        NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_message_session_created (session_id, created_at)
) COMMENT '会话中的对话消息';

CREATE TABLE review_log (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    user_id         BIGINT      NOT NULL,
    kp_id           BIGINT      NOT NULL,
    session_id      BIGINT      NULL,
    rating_user     VARCHAR(8)  NOT NULL COMMENT '用户确认的评分：AGAIN / HARD / GOOD / EASY',
    rating_ai       VARCHAR(8)  NULL COMMENT 'AI 建议的评分；调用失败时为空',
    state_before    VARCHAR(16) NOT NULL COMMENT '评分前的调度状态',
    is_first_review BOOLEAN     NOT NULL COMMENT '是否为该知识点的第一次学习',
    reviewed_at     DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_review_user_reviewed (user_id, reviewed_at),
    KEY idx_review_kp (kp_id)
) COMMENT '每次评分的流水';

-- 单用户模式的默认用户和默认设置。时区来自环境变量 WENGU_TIMEZONE（Flyway 占位符），只在第一次建库时生效，
-- 之后修改时区改的是 user_settings 中的数据
INSERT INTO `user` (id, username, created_at) VALUES (1, 'default', UTC_TIMESTAMP(3));
INSERT INTO user_settings (user_id, daily_new_limit, timezone, day_start_hour) VALUES (1, 3, '${default_timezone}', 4);
