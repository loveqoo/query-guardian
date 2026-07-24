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

CREATE TABLE IF NOT EXISTS saved_query (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    name             VARCHAR(100) NOT NULL,
    dialect          VARCHAR(16) NOT NULL,
    sql_text         TEXT NOT NULL,
    purpose_code     VARCHAR(64) NULL,
    lint_report_json TEXT NOT NULL,
    created_at       DATETIME(6) NOT NULL,
    updated_at       DATETIME(6) NOT NULL
);
