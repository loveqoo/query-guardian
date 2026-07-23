CREATE TABLE IF NOT EXISTS catalog_table (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    name          VARCHAR(128) NOT NULL UNIQUE,
    description   VARCHAR(500) NULL
);

CREATE TABLE IF NOT EXISTS catalog_column (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    catalog_table BIGINT NOT NULL,
    name          VARCHAR(128) NOT NULL,
    type          VARCHAR(64) NULL
);

CREATE TABLE IF NOT EXISTS catalog_constraint (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    catalog_table BIGINT NOT NULL,
    kind          VARCHAR(32) NOT NULL,
    column_name   VARCHAR(128) NULL,
    predicate_sql VARCHAR(500) NULL,
    purpose_code  VARCHAR(64) NULL
);

CREATE TABLE IF NOT EXISTS catalog_purpose (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    code          VARCHAR(64) NOT NULL UNIQUE,
    description   VARCHAR(255) NULL
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
