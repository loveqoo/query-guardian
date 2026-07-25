#!/usr/bin/env bash
# spec 008 §2.7 실행 격리를 **이미 떠 있는** config-db에 멱등 적용한다.
#
# /docker-entrypoint-initdb.d는 볼륨이 비어 있을 때만 실행되므로, 기존 개발 환경에서는 이 스크립트가 필요하다.
# (볼륨을 지워도 되면 `docker compose down -v && docker compose up -d`로 초기화 경로를 쓰면 된다.)
# 사용: docker/apply-exec-isolation.sh
set -euo pipefail
cd "$(dirname "$0")"

echo "== queryguardian_demo 스키마·qg_exec 계정 적용 =="
docker compose exec -T config-db mysql -uroot -proot < initdb/01-exec-isolation.sql

echo "== 검증: 실행 계정이 데모만 보고 설정 스키마는 못 보는가 =="
docker compose exec -T config-db mysql -uqg_exec -pqg-exec-demo -N -e \
  "SELECT CONCAT('users=', (SELECT COUNT(*) FROM queryguardian_demo.demo_users),
                 ' consents=', (SELECT COUNT(*) FROM queryguardian_demo.demo_marketing_consents),
                 ' events=', (SELECT COUNT(*) FROM queryguardian_demo.demo_user_events),
                 ' mask=', queryguardian_demo.mask_email('jimin@naver.com'))"

# 아래 항목은 **실패해야** 성공이다 (spec 008 §2.7-2). 적대 검토가 실측한 축들을 스크립트가 회귀로 잡는다.
exec_sql() { docker compose exec -T config-db mysql -uqg_exec -pqg-exec-demo "$@" 2>/dev/null; }

must_fail() { # must_fail <설명> <SQL>
  if exec_sql -e "$2" >/dev/null 2>&1; then
    echo "❌ $1 — 거부되어야 하는데 성공했다. 격리 실패." >&2
    exit 1
  fi
  echo "✅ $1 거부됨"
}

must_fail "설정 스키마 조회(app_user)"      "SELECT COUNT(*) FROM queryguardian.app_user"
must_fail "설정 스키마 조회(rule)"          "SELECT COUNT(*) FROM queryguardian.rule"
must_fail "계정 테이블 조회(mysql.user)"    "SELECT COUNT(*) FROM mysql.user"
must_fail "파일 반출(INTO OUTFILE)"         "SELECT 1 INTO OUTFILE '/tmp/qg-probe.txt'"
must_fail "행 잠금(FOR UPDATE)"             "START TRANSACTION READ ONLY; SELECT id FROM queryguardian_demo.demo_users LIMIT 1 FOR UPDATE"
must_fail "임시 테이블 생성"                "CREATE TEMPORARY TABLE queryguardian_demo.t (a INT)"

# LOAD_FILE은 FILE 권한이 없으면 오류 대신 NULL을 준다 — 값으로 확인해야 한다.
if [ "$(exec_sql -N -e "SELECT IFNULL(LOAD_FILE('/etc/hostname'), 'NULL')")" != "NULL" ]; then
  echo "❌ 실행 계정이 파일을 읽는다 (FILE 권한) — 격리 실패." >&2
  exit 1
fi
echo "✅ 파일 읽기(LOAD_FILE) NULL 확인"

# 마스킹 함수가 DEFINER면 root 권한으로 실행된다 → INVOKER여야 한다 (적대 검토 결함 7).
BAD_ROUTINES=$(docker compose exec -T config-db mysql -uroot -proot -N -e \
  "SELECT COUNT(*) FROM information_schema.ROUTINES
    WHERE ROUTINE_SCHEMA='queryguardian_demo' AND SECURITY_TYPE <> 'INVOKER'" 2>/dev/null | tr -d '[:space:]')
if [ "$BAD_ROUTINES" != "0" ]; then
  echo "❌ DEFINER 권한으로 도는 루틴이 $BAD_ROUTINES개 있다 — SQL SECURITY INVOKER를 붙여라." >&2
  exit 1
fi
echo "✅ 데모 루틴 전부 SQL SECURITY INVOKER"

echo "✅ 격리 검증 통과 — 실행 계정은 데모 스키마 SELECT + 마스킹 함수 EXECUTE만 가능"
