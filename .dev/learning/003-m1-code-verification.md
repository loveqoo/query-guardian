# 003 — M1 구현 적대 검증 결과와 반영

> 2026-07-24. opus 서브에이전트가 M1 코드(파서 어댑터·룰 엔진)를 §6 계약 대비 검증.
> 검증자는 추측 대신 **고정된 druid-1.2.28 jar에 직접 프로브를 컴파일해 확인**했다 — 이 방식이 신뢰도를 만들었다.
> 전 항목 수정 + `VerificationFindingsTest`(6건)로 회귀 고정. 스펙 §6.4/§6.5에 계약 반영.

## 발견 (F번호는 검증 리포트 기준)

| ID | 유형 | 내용 | 수정 |
|---|---|---|---|
| F1 | 우회+오탐 | Druid는 백틱을 **그대로 보존** → `` `user_events` ``는 카탈로그 매치 실패로 비거버넌스 취급(우회), 반대로 `` `event_date` ``는 요건 미충족(오차단) | 모든 식별자(테이블·컬럼·alias·한정자)를 IR 진입 전 `SQLUtils.normalize` |
| F2 | 우회 | 셀프 조인 `user_events a JOIN user_events b`에서 `a.event_date=...`가 물리명 매치로 b까지 면제 | ResolvedColumn.table을 물리명→**인스턴스 키(alias 우선)**로 변경, 룰은 인스턴스별 판정 |
| F3 | 우회 | `BETWEEN (SELECT...) AND (SELECT...)`·IN 리스트 피연산자의 서브쿼리가 스코프 미등록 → §6.2 위반 | Between/InList 분기에서 collectSubqueries 호출 |
| F4 | fail-open | 표현 불가 SELECT 변형(`VALUES ROW(...)` 등)이 빈 스코프로 조용히 통과 | SelectScope.unverifiable + 엔진의 무조건 BLOCK |
| F5 | 오탐 | 파생 테이블 alias가 거버넌스 테이블명과 겹치면 오차단 | TableRef.physical=false로 카탈로그 조회 제외 |
| F6 | 오탐 | 3-부 참조 `db.table.column`의 한정자 미해석 | qualifierOf가 SQLPropertyExpr owner 언랩 |

## 교훈

1. **스펙 계약 기반 테스트로는 "파서의 실제 표현"에서 오는 결함을 못 잡는다.** 우회 스위트 11건이 전부
   통과한 상태에서도 F1(백틱) 같은 치명적 우회가 살아 있었다. 계약은 SQL 의미론 차원이고, 결함은
   라이브러리 표현 차원(백틱 보존, alias 소실)에서 나왔다. → 새 파서 어댑터를 붙일 때마다
   **"그 파서가 식별자를 어떻게 표현하는가"를 프로브로 확인**하는 단계를 넣을 것.
2. **검증자에게 "추측 금지, 실제 아티팩트로 확인"을 요구하면 리포트 품질이 계단식으로 오른다.**
   이번 검증자는 druid jar에 Java 프로브를 돌려 백틱 보존을 실증했다 — 반박 불가능한 발견.
3. **fail-closed는 기본 경로가 아니라 모든 else 분기의 속성이어야 한다.** F4처럼 "여기 올 일 없다"는
   분기가 fail-open의 서식지다.
4. 식별자 정규화·인스턴스 귀속은 PG/Trino 어댑터에서도 똑같이 필요하다 — 어댑터 구현 체크리스트에 포함.
