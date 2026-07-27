-- 감사는 append-only다 — **규약이 아니라 DB가 강제한다** (spec 014 L7 · 백로그 D-D)
--
-- 이 파일은 **DDL만** 담는다(스키마 선택 없음). 두 곳이 같은 바이트를 읽는다:
--   1. docker/apply-audit-append-only.sh  — 배포/개발 환경에 적용
--   2. AuditAppendOnlyTest                — 이 DDL이 실제로 막는지 검증
-- 사본을 두 벌 두면 갈라지고, 갈라지면 테스트가 배포되지 않는 것을 검증하게 된다.
--
-- ## 왜 앱이 아니라 root가 거는가
--
-- 처음에는 `schema.sql`(앱이 기동 때 돌린다)에 넣었다가 전 테스트가 죽었다:
--     You do not have the SUPER privilege and binary logging is enabled
-- binlog가 켜진 MySQL에서 트리거 생성은 SUPER를 요구하는데 앱 계정(`qg`)에는 없다.
-- **줘서도 안 된다** — 감사를 못 지우게 하려고 앱 권한을 키우는 것은 방향이 거꾸로다.
-- 그래서 root가 건다. 앱은 이 트리거를 만들 수도, 내릴 수도 없다.
--
-- ## 왜 initdb가 아닌가
--
-- `/docker-entrypoint-initdb.d`는 **앱이 테이블을 만들기 전에** 돈다 — 걸 대상이 없다.
-- `apply-exec-isolation.sh`가 같은 이유로 존재한다. 그 관용구를 따른다.
--
-- ## 대가
--
-- 행 삭제가 필요한 마이그레이션은 트리거를 먼저 내려야 한다(root로).
-- 그것이 의도다 — 감사 행이 사라지는 일은 눈에 띄어야 한다.

DROP TRIGGER IF EXISTS execution_event_append_only_update;
CREATE TRIGGER execution_event_append_only_update
    BEFORE UPDATE ON execution_event
    FOR EACH ROW SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT = 'execution_event는 append-only 감사 이력입니다 — 수정할 수 없습니다';

DROP TRIGGER IF EXISTS execution_event_append_only_delete;
CREATE TRIGGER execution_event_append_only_delete
    BEFORE DELETE ON execution_event
    FOR EACH ROW SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT = 'execution_event는 append-only 감사 이력입니다 — 삭제할 수 없습니다';
