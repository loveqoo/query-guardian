-- spec 008 §2.7 실행 격리 3종 — 애플리케이션 버그와 무관한 최후 방어선.
--
-- 왜 설정 DB에 데모 데이터를 두지 않는가: 실행 계정이 설정 스키마를 읽을 수 있으면
-- `SELECT password_hash FROM app_user`·`SELECT tree_json FROM rule`이 한 번의 재작성 버그로 반출된다.
-- 그래서 (1) 별도 스키마 (2) 별도 계정(설정 스키마 무권한) (3) demo_table_map 총체성으로 3중 격리한다.
--
-- 이 파일은 컨테이너 초기화(/docker-entrypoint-initdb.d)에서 실행된다. 이미 볼륨이 있는 개발 환경에는
-- `docker/apply-exec-isolation.sh`로 같은 내용을 멱등 적용한다.
-- ⚠️ qg_exec 비밀번호는 데모 전용이다 — 운영 반입 금지.

CREATE DATABASE IF NOT EXISTS queryguardian_demo
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

USE queryguardian_demo;

-- ---- 데모 데이터 (카탈로그의 논리 테이블과 컬럼이 1:1) ----------------------
-- 논리 users → 물리 demo_users. 컬럼은 카탈로그 등록분과 정확히 일치시킨다
-- (없는 컬럼을 두면 "카탈로그엔 없는데 실행되는" 컬럼이 생겨 판정 사각이 된다).

CREATE TABLE IF NOT EXISTS demo_users (
    id         BIGINT PRIMARY KEY,
    email      VARCHAR(255) NOT NULL,
    name       VARCHAR(100) NOT NULL,
    phone      VARCHAR(20)  NOT NULL,
    ssn        VARCHAR(13)  NOT NULL,
    created_at DATETIME     NOT NULL
);

CREATE TABLE IF NOT EXISTS demo_marketing_consents (
    id         BIGINT PRIMARY KEY,
    user_id    BIGINT   NOT NULL,
    consent_yn CHAR(1)  NOT NULL,
    consent_at DATETIME NOT NULL,
    KEY idx_user (user_id)
);

CREATE TABLE IF NOT EXISTS demo_user_events (
    id         BIGINT PRIMARY KEY,
    event_date DATE NOT NULL,
    KEY idx_event_date (event_date)
);

INSERT IGNORE INTO demo_users (id, email, name, phone, ssn, created_at) VALUES
    (1,  'jimin@naver.com',    '김지민', '010-1234-5678', '900101-1234567', '2025-11-02 10:12:00'),
    (2,  'seoyeon@gmail.com',  '이서연', '010-2345-6789', '920315-2234567', '2025-11-08 14:31:00'),
    (3,  'minjun@naver.com',   '박민준', '010-3456-7890', '880722-1345678', '2025-12-01 09:05:00'),
    (4,  'hayoon@kakao.com',   '최하윤', '010-4567-8901', '950214-2456789', '2025-12-14 16:44:00'),
    (5,  'doyun@gmail.com',    '정도윤', '010-5678-9012', '870930-1567890', '2026-01-03 11:20:00'),
    (6,  'ssong@naver.com',    '송지호', '010-6789-0123', '910408-1678901', '2026-01-19 13:02:00'),
    (7,  'eunseo@daum.net',    '한은서', '010-7890-1234', '930611-2789012', '2026-02-07 08:58:00'),
    (8,  'jiwoo@gmail.com',    '오지우', '010-8901-2345', '960125-2890123', '2026-02-22 17:36:00'),
    (9,  'taehyun@naver.com',  '윤태현', '010-9012-3456', '890803-1901234', '2026-03-11 10:47:00'),
    (10, 'chaewon@kakao.com',  '임채원', '010-0123-4567', '940519-2012345', '2026-04-02 15:15:00'),
    (11, 'gunwoo@naver.com',   '조건우', '010-1122-3344', '860227-1123456', '2026-05-16 12:09:00'),
    (12, 'nayeon@gmail.com',   '강나연', '010-2233-4455', '970704-2234567', '2026-06-28 09:33:00');

-- 동의: Y 8건 / N 4건 — FILTER 주입이 실제로 행을 줄이는지 눈으로 확인할 수 있게 섞는다.
INSERT IGNORE INTO demo_marketing_consents (id, user_id, consent_yn, consent_at) VALUES
    (1,  1,  'Y', '2025-11-02 10:20:00'),
    (2,  2,  'Y', '2025-11-08 14:40:00'),
    (3,  3,  'N', '2025-12-01 09:10:00'),
    (4,  4,  'Y', '2025-12-14 16:50:00'),
    (5,  5,  'Y', '2026-01-03 11:25:00'),
    (6,  6,  'N', '2026-01-19 13:10:00'),
    (7,  7,  'Y', '2026-02-07 09:05:00'),
    (8,  8,  'Y', '2026-02-22 17:40:00'),
    (9,  9,  'N', '2026-03-11 10:55:00'),
    (10, 10, 'Y', '2026-04-02 15:20:00'),
    (11, 11, 'Y', '2026-05-16 12:15:00'),
    (12, 12, 'N', '2026-06-28 09:40:00');

-- 이벤트 1,500행 — 행 상한(기본 1,000)·truncated 판정을 **실제 데이터로** 검증할 수 있어야 한다.
SET SESSION cte_max_recursion_depth = 2000; -- 기본 1,000이라 1,500행 생성이 중단된다
INSERT IGNORE INTO demo_user_events (id, event_date)
WITH RECURSIVE seq (n) AS (
    SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 1500
)
SELECT n, DATE_ADD('2026-01-01', INTERVAL (n % 30) DAY) FROM seq;

-- ---- 마스킹 강제식이 호출하는 함수 -----------------------------------------
-- 카탈로그의 MASK 정의(`mask_email({col})`)가 실제로 동작해야 디자인의 `j***@naver.com`이 진짜가 된다.
-- DETERMINISTIC 선언이 있어야 binlog 활성 상태에서도 생성된다.
-- SQL SECURITY INVOKER 필수: 생략하면 MySQL 기본값이 DEFINER이고 definer는 이 스크립트를 돌린 root가 되어,
-- 데모 스키마에 루틴이 하나 추가되는 순간 qg_exec가 그것을 root 권한으로 호출할 수 있게 된다.

DROP FUNCTION IF EXISTS mask_email;
CREATE FUNCTION mask_email(v VARCHAR(255)) RETURNS VARCHAR(255)
    DETERMINISTIC SQL SECURITY INVOKER
    RETURN CASE
        WHEN v IS NULL THEN NULL
        WHEN LOCATE('@', v) < 2 THEN '***'
        ELSE CONCAT(LEFT(v, 1), '***', SUBSTRING(v, LOCATE('@', v)))
    END;

DROP FUNCTION IF EXISTS mask_phone;
CREATE FUNCTION mask_phone(v VARCHAR(32)) RETURNS VARCHAR(32)
    DETERMINISTIC SQL SECURITY INVOKER
    RETURN CASE
        WHEN v IS NULL THEN NULL
        WHEN CHAR_LENGTH(v) < 4 THEN '***'
        ELSE CONCAT(REPEAT('*', CHAR_LENGTH(v) - 4), RIGHT(v, 4))
    END;

DROP FUNCTION IF EXISTS mask_name;
CREATE FUNCTION mask_name(v VARCHAR(100)) RETURNS VARCHAR(100)
    DETERMINISTIC SQL SECURITY INVOKER
    RETURN CASE
        WHEN v IS NULL THEN NULL
        WHEN CHAR_LENGTH(v) < 2 THEN '*'
        ELSE CONCAT(LEFT(v, 1), REPEAT('*', CHAR_LENGTH(v) - 1))
    END;

-- ---- 실행 전용 계정 --------------------------------------------------------
-- SELECT + EXECUTE만, 데모 스키마에만. 설정 스키마(queryguardian)에는 **어떤 권한도 주지 않는다**.
-- FILE(=LOAD_FILE/OUTFILE)·PROCESS·SUPER 없음 → 위생 게이트가 뚫려도 파일 반출이 불가능하다.

CREATE USER IF NOT EXISTS 'qg_exec'@'%' IDENTIFIED BY 'qg-exec-demo';
ALTER USER 'qg_exec'@'%' IDENTIFIED BY 'qg-exec-demo';
REVOKE ALL PRIVILEGES, GRANT OPTION FROM 'qg_exec'@'%';
GRANT SELECT ON queryguardian_demo.* TO 'qg_exec'@'%';
-- EXECUTE는 스키마 전체가 아니라 **함수별로** 준다 — `ON queryguardian_demo.*`는 나중에 추가될 루틴까지 포함한다.
GRANT EXECUTE ON FUNCTION queryguardian_demo.mask_email TO 'qg_exec'@'%';
GRANT EXECUTE ON FUNCTION queryguardian_demo.mask_phone TO 'qg_exec'@'%';
GRANT EXECUTE ON FUNCTION queryguardian_demo.mask_name  TO 'qg_exec'@'%';
FLUSH PRIVILEGES;
