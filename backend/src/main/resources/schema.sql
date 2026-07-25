CREATE TABLE IF NOT EXISTS catalog_table (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    name          VARCHAR(128) NOT NULL UNIQUE,
    description   VARCHAR(500) NULL
);

CREATE TABLE IF NOT EXISTS catalog_column (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    catalog_table BIGINT NOT NULL,
    name          VARCHAR(128) NOT NULL,
    type          VARCHAR(64) NULL,
    is_pii        BOOLEAN NOT NULL DEFAULT FALSE,
    cls           VARCHAR(16) NOT NULL DEFAULT 'STRING'
);

CREATE TABLE IF NOT EXISTS catalog_purpose (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    code          VARCHAR(64) NOT NULL UNIQUE,
    description   VARCHAR(255) NULL
);

CREATE TABLE IF NOT EXISTS constraint_def (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    cls           VARCHAR(16) NOT NULL,
    kind          VARCHAR(16) NOT NULL,
    name          VARCHAR(128) NOT NULL,
    description   VARCHAR(500) NULL,
    expression    VARCHAR(500) NULL
);

CREATE TABLE IF NOT EXISTS constraint_mapping (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    column_id     BIGINT NOT NULL,
    def_id        BIGINT NOT NULL,
    purpose_code  VARCHAR(64) NULL,
    params_json   VARCHAR(500) NULL,
    CONSTRAINT uq_mapping UNIQUE (column_id, def_id, purpose_code)
);

CREATE TABLE IF NOT EXISTS rule (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    name          VARCHAR(128) NOT NULL,
    scope         VARCHAR(16) NOT NULL,
    server        VARCHAR(64) NULL,
    enabled       BOOLEAN NOT NULL DEFAULT TRUE,
    tree_json     TEXT NOT NULL,
    hit_count     BIGINT NOT NULL DEFAULT 0
);

-- 승인 요청 (spec 005) --------------------------------------------------------
CREATE TABLE IF NOT EXISTS approval_request (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    purpose_title  VARCHAR(200) NOT NULL,
    purpose_code   VARCHAR(64) NOT NULL,
    requester      VARCHAR(64) NOT NULL,
    status         VARCHAR(16) NOT NULL,
    current_step   INT NOT NULL,
    submitted_at   DATETIME(6) NOT NULL,
    decided_at     DATETIME(6) NULL,
    version        BIGINT NULL
);

CREATE TABLE IF NOT EXISTS request_table (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id BIGINT NOT NULL,
    table_idx  INT NOT NULL,
    db         VARCHAR(64) NULL,
    table_name VARCHAR(128) NOT NULL
);

CREATE TABLE IF NOT EXISTS request_rule (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id         BIGINT NOT NULL,
    rule_idx           INT NOT NULL,
    rule_id            BIGINT NOT NULL,
    rule_name          VARCHAR(128) NOT NULL,
    severity_summary   VARCHAR(16) NOT NULL,
    tree_json_snapshot TEXT NOT NULL,
    forced             BOOLEAN NOT NULL
);

CREATE TABLE IF NOT EXISTS request_business_req (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id BIGINT NOT NULL,
    req_idx    INT NOT NULL,
    code       VARCHAR(64) NOT NULL
);

CREATE TABLE IF NOT EXISTS request_approver (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id  BIGINT NOT NULL,
    step        INT NOT NULL,
    approver_id VARCHAR(32) NOT NULL,
    name       VARCHAR(64) NOT NULL,
    role       VARCHAR(64) NOT NULL,
    decision   VARCHAR(16) NOT NULL,
    decided_at DATETIME(6) NULL,
    CONSTRAINT uq_request_step UNIQUE (request_id, step)
);

CREATE TABLE IF NOT EXISTS approval_event (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id BIGINT NOT NULL,
    step       INT NULL,
    actor      VARCHAR(64) NOT NULL,
    action     VARCHAR(16) NOT NULL,
    note       VARCHAR(500) NULL,
    at         DATETIME(6) NOT NULL
);

CREATE TABLE IF NOT EXISTS query_review_event (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    query_id           BIGINT NOT NULL,
    actor              VARCHAR(64) NOT NULL,
    decision           VARCHAR(16) NOT NULL,
    note               VARCHAR(500) NULL,
    sql_hash           VARCHAR(64) NOT NULL,
    lint_snapshot_json TEXT NOT NULL,
    at                 DATETIME(6) NOT NULL
);

CREATE TABLE IF NOT EXISTS saved_query (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    name             VARCHAR(100) NOT NULL,
    dialect          VARCHAR(16) NOT NULL,
    sql_text         TEXT NOT NULL,
    purpose_code     VARCHAR(64) NULL,
    request_id       BIGINT NOT NULL,
    review_status    VARCHAR(24) NOT NULL DEFAULT 'PENDING_REVIEW',
    reviewer         VARCHAR(64) NULL,
    reviewed_at      DATETIME(6) NULL,
    review_note      VARCHAR(500) NULL,
    lint_report_json TEXT NOT NULL,
    created_at       DATETIME(6) NOT NULL,
    updated_at       DATETIME(6) NOT NULL
);

-- 인증·권한 (spec 007) -------------------------------------------------------
CREATE TABLE IF NOT EXISTS app_user (
    id            VARCHAR(64) PRIMARY KEY,
    display_name  VARCHAR(64) NOT NULL,
    title         VARCHAR(64) NOT NULL,
    role          VARCHAR(16) NOT NULL,
    password_hash VARCHAR(120) NOT NULL,
    enabled       BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS user_server_permission (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id    VARCHAR(64) NOT NULL,
    server_key VARCHAR(64) NOT NULL,
    allowed    BOOLEAN NOT NULL,
    CONSTRAINT uq_user_server UNIQUE (user_id, server_key)
);

CREATE TABLE IF NOT EXISTS user_table_permission (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id    VARCHAR(64) NOT NULL,
    table_name VARCHAR(128) NOT NULL,
    allowed    BOOLEAN NOT NULL,
    CONSTRAINT uq_user_table UNIQUE (user_id, table_name)
);

-- 실행 격리 (spec 008 §2.7-3) ------------------------------------------------
-- 논리 테이블 ↔ 데모 물리 테이블. 매핑표이면서 **실행 허용목록을 겸한다** —
-- 미매핑 테이블이 하나라도 있으면 실행을 거부해야 원래 이름 그대로 실행되는 경로가 없다.
CREATE TABLE IF NOT EXISTS demo_table_map (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    logical_name  VARCHAR(128) NOT NULL,
    physical_name VARCHAR(128) NOT NULL,
    CONSTRAINT uq_demo_logical UNIQUE (logical_name)
);

INSERT IGNORE INTO demo_table_map (logical_name, physical_name) VALUES
    ('users', 'demo_users'),
    ('marketing_consents', 'demo_marketing_consents'),
    ('user_events', 'demo_user_events');

CREATE TABLE IF NOT EXISTS permission_change_event (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    target_user_id  VARCHAR(64) NOT NULL,
    actor           VARCHAR(64) NOT NULL,
    scope           VARCHAR(16) NOT NULL,
    target          VARCHAR(128) NOT NULL,
    before_allowed  BOOLEAN NULL,
    after_allowed   BOOLEAN NOT NULL,
    at              DATETIME(6) NOT NULL
);
