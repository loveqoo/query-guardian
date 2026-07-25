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
    sql_text         MEDIUMTEXT NOT NULL,
    purpose_code     VARCHAR(64) NULL,
    request_id       BIGINT NOT NULL,
    review_status    VARCHAR(24) NOT NULL DEFAULT 'PENDING_REVIEW',
    reviewer         VARCHAR(64) NULL,
    reviewed_at      DATETIME(6) NULL,
    review_note      VARCHAR(500) NULL,
    lint_report_json MEDIUMTEXT NOT NULL,
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

-- 실행 감사 (spec 008 §6) — append-only. 원본·재작성 SQL은 **TEXT**(VARCHAR면 잘려 사후 검증이 불가능하다).
-- **결과 행은 저장하지 않는다**(불변식) — 감사 기록이 또 다른 유출원이 되면 안 된다.
-- error_detail은 SQLState·vendor code·정제 메시지만 담고 STEWARD/ADMIN에게만 노출한다
-- (MySQL 오류 메시지는 데이터 값을 에코한다: `Truncated incorrect ... value: '...'`).
CREATE TABLE IF NOT EXISTS execution_event (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    -- 미리보기(preview-rewrite)는 저장된 쿼리가 없으므로 NULL이다
    query_id      BIGINT NULL,
    actor         VARCHAR(64) NOT NULL,
    outcome       VARCHAR(16) NOT NULL,
    -- MEDIUMTEXT: 재작성 SQL은 원본보다 커지므로 TEXT(65535 B)는 입력 상한(60,000 B)과 너무 가깝다
    original_sql  MEDIUMTEXT NOT NULL,
    rewritten_sql MEDIUMTEXT NULL,
    applied_json  MEDIUMTEXT NULL,
    row_count     INT NULL,
    elapsed_ms    BIGINT NULL,
    -- 세 값을 나눠 적는다: 설정 상한(configured_cap), 적용 상한(effective_limit), 초과 행 존재.
    -- 예전에는 `truncated` 하나여서 false가 "상한 없음"과 "상한 안에 다 들어왔음"을 구분하지 못했다.
    -- more_rows_exist가 NULL이면 **알 수 없음** — LIMIT 0이면 초과 여부를 조회조차 하지 않는다.
    effective_limit BIGINT NULL,
    configured_cap  BIGINT NULL,
    more_rows_exist BOOLEAN NULL,
    error_code    VARCHAR(32) NULL,
    error_detail  MEDIUMTEXT NULL,
    at            DATETIME(6) NOT NULL,
    KEY idx_execution_query (query_id),
    KEY idx_execution_actor (actor)
);

-- `truncated` 하나를 `effective_limit`+`configured_cap`+`more_rows_exist` 셋으로 쪼갠 이력 마이그레이션.
-- `CREATE TABLE IF NOT EXISTS`는 **이미 있는 테이블에 컬럼을 더해주지 않는다** — 이 스크립트는 매 기동마다
-- 돌므로(`spring.sql.init.mode=always`) 기존 DB는 새 컬럼 없이 남고 감사 INSERT가 죽는다. 감사가 죽으면
-- 실행이 무기록으로 통과하므로, 여기서 멱등 DDL로 메운다. 값은 옮긴 뒤 옛 컬럼을 지운다(기록 보존).
SET @qg_ddl := IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'execution_event' AND column_name = 'effective_limit') = 0,
    'ALTER TABLE execution_event ADD COLUMN effective_limit BIGINT NULL', 'DO 0');
PREPARE qg_stmt FROM @qg_ddl;
EXECUTE qg_stmt;
DEALLOCATE PREPARE qg_stmt;

SET @qg_ddl := IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'execution_event' AND column_name = 'configured_cap') = 0,
    'ALTER TABLE execution_event ADD COLUMN configured_cap BIGINT NULL', 'DO 0');
PREPARE qg_stmt FROM @qg_ddl;
EXECUTE qg_stmt;
DEALLOCATE PREPARE qg_stmt;

-- 오늘 만들었다 이름을 바로 고친 컬럼 — `row_cap`은 "설정 상한"과 "적용 상한"을 뭉갠 이름이었다(D5)
SET @qg_ddl := IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'execution_event' AND column_name = 'row_cap') = 1,
    'ALTER TABLE execution_event DROP COLUMN row_cap', 'DO 0');
PREPARE qg_stmt FROM @qg_ddl;
EXECUTE qg_stmt;
DEALLOCATE PREPARE qg_stmt;

SET @qg_ddl := IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'execution_event' AND column_name = 'more_rows_exist') = 0,
    'ALTER TABLE execution_event ADD COLUMN more_rows_exist BOOLEAN NULL', 'DO 0');
PREPARE qg_stmt FROM @qg_ddl;
EXECUTE qg_stmt;
DEALLOCATE PREPARE qg_stmt;

-- `query_id`는 미리보기(저장 쿼리 없음) 때문에 **nullable**이어야 한다. 처음엔 `NOT NULL`로 만들었고
-- 선언만 `NULL`로 바꿨는데 `CREATE TABLE IF NOT EXISTS`는 기존 테이블을 고치지 않는다 — 기존 DB에서는
-- 미리보기 감사 INSERT가 전부 죽고, 차단 예외가 무결성 예외로 **바꿔치기되어 403이 500이 된다**
-- (적대 검토 D2 실측). nullability도 컬럼 추가와 똑같이 마이그레이션 대상이다.
SET @qg_ddl := IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'execution_event'
        AND column_name = 'query_id' AND is_nullable = 'NO') = 1,
    'ALTER TABLE execution_event MODIFY COLUMN query_id BIGINT NULL', 'DO 0');
PREPARE qg_stmt FROM @qg_ddl;
EXECUTE qg_stmt;
DEALLOCATE PREPARE qg_stmt;

-- 감사 본문 컬럼은 **MEDIUMTEXT**다. TEXT(65535 B)는 입력 상한(60,000 B)과 너무 가깝고,
-- 재작성 SQL은 **원본보다 커진다**(`email` → `mask_email(\`demo_users\`.\`email\`) AS \`email\``).
-- 즉 입력을 통과한 SQL이 감사 INSERT에서 죽을 수 있고, 감사가 죽는 것은 무기록 실행을 뜻한다.
-- 상한 하나를 다른 상한에 매달지 않고 컬럼을 넉넉하게 잡아 결합을 끊는다.
SET @qg_ddl := IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'execution_event'
        AND column_name = 'original_sql' AND data_type = 'text') = 1,
    'ALTER TABLE execution_event MODIFY COLUMN original_sql MEDIUMTEXT NOT NULL', 'DO 0');
PREPARE qg_stmt FROM @qg_ddl;
EXECUTE qg_stmt;
DEALLOCATE PREPARE qg_stmt;

SET @qg_ddl := IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'execution_event'
        AND column_name = 'rewritten_sql' AND data_type = 'text') = 1,
    'ALTER TABLE execution_event MODIFY COLUMN rewritten_sql MEDIUMTEXT NULL', 'DO 0');
PREPARE qg_stmt FROM @qg_ddl;
EXECUTE qg_stmt;
DEALLOCATE PREPARE qg_stmt;

SET @qg_ddl := IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'execution_event'
        AND column_name = 'applied_json' AND data_type = 'text') = 1,
    'ALTER TABLE execution_event MODIFY COLUMN applied_json MEDIUMTEXT NULL', 'DO 0');
PREPARE qg_stmt FROM @qg_ddl;
EXECUTE qg_stmt;
DEALLOCATE PREPARE qg_stmt;

SET @qg_ddl := IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'execution_event'
        AND column_name = 'error_detail' AND data_type = 'text') = 1,
    'ALTER TABLE execution_event MODIFY COLUMN error_detail MEDIUMTEXT NULL', 'DO 0');
PREPARE qg_stmt FROM @qg_ddl;
EXECUTE qg_stmt;
DEALLOCATE PREPARE qg_stmt;

-- 저장 쿼리의 본문·판정 결과도 입력 상한(60,000 B)에서 파생된다. `lint_report_json`은 스코프 수 × 마스킹
-- 컬럼 수로 커지므로 TEXT(65,535 B)로는 저장이 `Data too long`으로 죽을 수 있다(적대 검토 D8).
SET @qg_ddl := IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'saved_query'
        AND column_name = 'sql_text' AND data_type = 'text') = 1,
    'ALTER TABLE saved_query MODIFY COLUMN sql_text MEDIUMTEXT NOT NULL', 'DO 0');
PREPARE qg_stmt FROM @qg_ddl;
EXECUTE qg_stmt;
DEALLOCATE PREPARE qg_stmt;

SET @qg_ddl := IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'saved_query'
        AND column_name = 'lint_report_json' AND data_type = 'text') = 1,
    'ALTER TABLE saved_query MODIFY COLUMN lint_report_json MEDIUMTEXT NOT NULL', 'DO 0');
PREPARE qg_stmt FROM @qg_ddl;
EXECUTE qg_stmt;
DEALLOCATE PREPARE qg_stmt;


-- 옛 `truncated`가 남아 있으면 값을 옮기고(true였던 기록 = 초과 행 있었음) 컬럼을 지운다
SET @qg_ddl := IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'execution_event' AND column_name = 'truncated') = 1,
    'UPDATE execution_event SET more_rows_exist = truncated WHERE more_rows_exist IS NULL', 'DO 0');
PREPARE qg_stmt FROM @qg_ddl;
EXECUTE qg_stmt;
DEALLOCATE PREPARE qg_stmt;

SET @qg_ddl := IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'execution_event' AND column_name = 'truncated') = 1,
    'ALTER TABLE execution_event DROP COLUMN truncated', 'DO 0');
PREPARE qg_stmt FROM @qg_ddl;
EXECUTE qg_stmt;
DEALLOCATE PREPARE qg_stmt;

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
