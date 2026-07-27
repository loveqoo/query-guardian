#!/usr/bin/env bash
# 감사 테이블에 append-only 트리거를 건다 (spec 014 L7).
#
# **앱을 한 번 기동한 뒤에 실행한다** — `execution_event` 테이블이 있어야 트리거를 걸 수 있고,
# 그 테이블은 앱의 `schema.sql`이 만든다. 멱등하므로 여러 번 돌려도 된다.
#
# 사용: docker/apply-audit-append-only.sh
set -euo pipefail
cd "$(dirname "$0")"

if ! docker compose exec -T config-db mysql -uroot -proot -N -e \
     "SELECT 1 FROM information_schema.tables WHERE table_schema='queryguardian' AND table_name='execution_event'" \
     | grep -q 1; then
  echo "!! execution_event 테이블이 없다 — 백엔드를 한 번 기동한 뒤 다시 실행하라" >&2
  exit 1
fi

echo "== 감사 append-only 트리거 적용 =="
docker compose exec -T config-db mysql -uroot -proot queryguardian < audit-append-only.sql

echo "== 확인 =="
docker compose exec -T config-db mysql -uroot -proot -N -e \
  "SELECT trigger_name FROM information_schema.triggers
    WHERE trigger_schema='queryguardian' AND event_object_table='execution_event'"
