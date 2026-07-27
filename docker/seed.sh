#!/usr/bin/env bash
# 데모 상태 시드 — 카탈로그(테이블·제약 정의·매핑·purpose) + 사용자 규칙.
# 백엔드가 http://localhost:8080 에서 떠 있어야 한다. 재실행 시 이미 존재하면 400/409는 무시된다.
# 사용: docker/seed.sh   (재현 가능 — 회고 003/004의 수동 curl 시드 대체)
set -u
API="${QG_API:-http://localhost:8080/api}"
JAR_DIR="${TMPDIR:-/tmp}/qg-seed-cookies"
mkdir -p "$JAR_DIR"
jq_id() { python3 -c 'import sys,json;print(json.load(sys.stdin).get("id",""))'; }

# spec 007: 모든 API가 인증을 요구한다. 사용자별 쿠키 자를 만들어 재사용한다.
login() { # login <userId>
  local u="$1"
  [ -f "$JAR_DIR/$u" ] || curl -s -c "$JAR_DIR/$u" -X POST "$API/auth/login" \
    -H 'Content-Type: application/json' -d "{\"userId\":\"$u\",\"password\":\"qg-demo\"}" >/dev/null
}
as() { # as <userId> <path> <json>
  local u="$1"; shift; login "$u"
  curl -s -b "$JAR_DIR/$u" -X POST "$API/$1" -H 'Content-Type: application/json' -d "$2"
}
# 카탈로그·규칙 쓰기는 ADMIN 세션으로
post() { as adm1 "$1" "$2"; }

echo "== purposes =="
post "catalog/purposes" '{"code":"marketing","description":"마케팅 조회"}' >/dev/null

echo "== tables =="
USERS=$(post "catalog/tables" '{"name":"users","description":"사용자 (PII 보유)","columns":[
  {"name":"id","type":"BIGINT","isPii":false},
  {"name":"email","type":"VARCHAR(255)","isPii":true},
  {"name":"name","type":"VARCHAR(100)","isPii":true},
  {"name":"phone","type":"VARCHAR(20)","isPii":true},
  {"name":"ssn","type":"VARCHAR(13)","isPii":true},
  {"name":"created_at","type":"DATETIME","isPii":false}]}')
MC=$(post "catalog/tables" '{"name":"marketing_consents","description":"마케팅 수신 동의","columns":[
  {"name":"id","type":"BIGINT","isPii":false},
  {"name":"user_id","type":"BIGINT","isPii":false},
  {"name":"consent_yn","type":"CHAR(1)","isPii":false},
  {"name":"consent_at","type":"DATETIME","isPii":false}]}')
UE=$(post "catalog/tables" '{"name":"user_events","description":"유저 이벤트 (파티션)","columns":[
  {"name":"id","type":"BIGINT","isPii":false},
  {"name":"event_date","type":"DATE","isPii":false}]}')

col() { python3 -c "import sys,json;d=json.load(sys.stdin);print([c['id'] for c in d['columns'] if c['name']=='$1'][0])"; }
SSN=$(echo "$USERS" | col ssn); EMAIL=$(echo "$USERS" | col email)
CONSENT=$(echo "$MC" | col consent_yn); USERID=$(echo "$MC" | col user_id)
EVENTDATE=$(echo "$UE" | col event_date)

echo "== constraint defs =="
BLOCK=$(post "catalog/defs" '{"cls":"PII","kind":"BLOCK","name":"조회 전면 차단","description":"해당 컬럼 조회 자체를 차단"}' | jq_id)
MASK=$(post "catalog/defs" '{"cls":"PII","kind":"MASK","name":"도메인만 노출 (a***@x.com)","description":"이메일 로컬파트 마스킹","expression":"mask_email({col})"}' | jq_id)
FILTER=$(post "catalog/defs" '{"cls":"STRING","kind":"FILTER","name":"동의 필수","description":"동의한 사용자만","expression":"{col} = '"'"'Y'"'"'"}' | jq_id)
JOINDEF=$(post "catalog/defs" '{"cls":"KEY","kind":"JOIN","name":"참조 키로 조인","description":"외래키 조인","expression":"{col} = :ref"}' | jq_id)
PART=$(post "catalog/defs" '{"cls":"DATETIME","kind":"PARTITION","name":"파티션 키 필수","description":"파티션 컬럼 조건이 없으면 거부"}' | jq_id)

echo "== mappings =="
post "catalog/mappings" "{\"columnId\":$SSN,\"defId\":$BLOCK}" >/dev/null
post "catalog/mappings" "{\"columnId\":$EMAIL,\"defId\":$MASK}" >/dev/null
post "catalog/mappings" "{\"columnId\":$CONSENT,\"defId\":$FILTER,\"purposeCode\":\"marketing\"}" >/dev/null
post "catalog/mappings" "{\"columnId\":$USERID,\"defId\":$JOINDEF}" >/dev/null
post "catalog/mappings" "{\"columnId\":$EVENTDATE,\"defId\":$PART}" >/dev/null

echo "== rules =="
# r1: 마케팅 동의 사용자 한정 (MULTI) — 조인 + 동의 필수
post "rules" "{\"name\":\"마케팅 동의 사용자 한정\",\"scope\":\"MULTI\",\"server\":\"mysql-prod\",\"tree\":
  {\"node\":\"group\",\"combinator\":\"all\",\"children\":[
    {\"node\":\"cond\",\"op\":\"joins\",\"severity\":\"BLOCK\",\"table\":\"marketing_consents\",\"column\":\"user_id\",\"refTable\":\"users\",\"refColumn\":\"id\"},
    {\"node\":\"cond\",\"op\":\"requires\",\"severity\":\"BLOCK\",\"table\":\"marketing_consents\",\"column\":\"consent_yn\",\"defId\":$FILTER}]}}" >/dev/null
# r2: PII 마스킹 필수 (SINGLE) — must_be_masked 전용.
#     ⚠️ 이 주석은 "미강제(판정 미구현) 데모"라고 적혀 있었으나 **낡았다** — spec 008 M1에서
#     must_be_masked가 판정으로 전환됐다. 즉 이 규칙은 실제로 **차단한다**(BLOCK).
post "rules" "{\"name\":\"PII 마스킹 필수\",\"scope\":\"SINGLE\",\"server\":\"mysql-prod\",\"tree\":
  {\"node\":\"group\",\"combinator\":\"all\",\"children\":[
    {\"node\":\"cond\",\"op\":\"must_be_masked\",\"severity\":\"BLOCK\",\"table\":\"users\",\"column\":\"email\",\"defId\":$MASK}]}}" >/dev/null

echo "== approvals (spec 005) =="
# 승인 요청 생성 → 순차 승인 완료 (에디터에서 바로 쓸 수 있는 승인된 요청)
post_as() { as "$2" "$1" "$3"; }  # 인수 순서 유지 (path, actor, body)
REQ=$(post_as "approvals" "u1" '{"purposeTitle":"Q3 마케팅 캠페인 대상자 추출","purposeCode":"marketing",
  "tables":[{"tableName":"users"},{"tableName":"marketing_consents"},{"tableName":"user_events"}],
  "ruleIds":[],"businessReqs":["marketing","pii"],
  "approvers":[{"step":1,"approverId":"ap1"},{"step":2,"approverId":"ap2"}]}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["summary"]["id"])')
post_as "approvals/$REQ/approve" "ap1" '{}' >/dev/null
post_as "approvals/$REQ/approve" "ap2" '{}' >/dev/null
# 대기 중인 요청 하나 더 (승인 화면 데모용)
post_as "approvals" "u2" '{"purposeTitle":"VIP 고객 리텐션 분석","purposeCode":"marketing",
  "tables":[{"tableName":"users"}],"ruleIds":[],"businessReqs":["pii","mask"],
  "approvers":[{"step":1,"approverId":"ap2"}]}' >/dev/null

echo "로그인 데모: 아이디 u1(분석가)/u4·ap1~ap4(STEWARD)/adm1(ADMIN), 비밀번호 qg-demo — 운영 반입 금지"
echo "seed 완료. rules: $(curl -s -b "$JAR_DIR/adm1" "$API/rules" | python3 -c 'import sys,json;print(len(json.load(sys.stdin)))')건, approvals: $(curl -s -b "$JAR_DIR/adm1" "$API/approvals" | python3 -c 'import sys,json;print(len(json.load(sys.stdin)))')건 (승인됨 REQ=$REQ)"
