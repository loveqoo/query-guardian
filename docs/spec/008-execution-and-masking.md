# 008 — 쿼리 실행 · 마스킹 재작성

> 상태: **초안 (인간 검토 대기)**
> 작성: 2026-07-25 · 근거: learning 004 §2.5(디자인의 실행·마스킹), spec 002 §9-1(단계적 강제 — 재작성 보류 결정), spec 005 §2(review_status 승격 예고)
> 사용자 지시 순서 ③. spec 002가 미룬 **재작성(rewrite)** 을 도입하고 디자인의 실행 결과 화면을 실제로 만든다.

## 1. 목표

1. **IR→SQL 재작성 엔진**: MASK는 SELECT 절 치환, FILTER·INTEGRITY는 WHERE 술어 주입, LIMIT 상한 주입.
2. **쿼리 실행**: 재작성된 SQL을 실제로 실행해 결과를 반환. 디자인의 마스킹된 결과(`j***@naver.com`)가 진짜가 된다.
3. **안전장치 4종**(사용자 확정): 검토 상태 게이트 · 행 상한·타임아웃 · 읽기 전용 연결 · 실행 감사 로그.
4. `must_be_masked` 판정 구현(spec 004에서 미판정으로 남긴 것) — 재작성이 있으므로 "마스킹 안 됨"을 정의할 수 있다.

## 2. 범위 / 비범위

### 범위
- **재작성 엔진**(`SqlRewriter`): MASK(SELECT 치환)·FILTER/INTEGRITY(WHERE 주입)·LIMIT 상한 주입
- **실행**: 설정 DB(동일 MySQL)에 **데모 데이터 테이블** 생성 후 그 위에서 실행 (사용자 확정: 동일 DB로 시작)
- **실행 게이트**: 인증 → 데이터 권한 → 룰 → **검토 승인(APPROVED)** → 실행
- 읽기 전용 실행 연결(별도 DataSource·읽기 전용 트랜잭션), 행 상한·질의 타임아웃
- `execution_event` append-only 감사(누가·언제·원본 SQL·재작성 SQL·행 수·소요·결과)
- `must_be_masked` 판정: MASK 매핑 컬럼이 **재작성 없이 원본으로 조회되면** 위반
- 에디터 "실행" 버튼·실행 결과 탭 실동작(재작성 SQL 표시 포함)

### 비범위
- 별도 원격 대상 DB 등록·자격증명 보관(사용자 확정: 동일 DB로 시작 → ④에서 재검토)
- 결과 내보내기(CSV·다운로드), 페이지네이션(첫 N행만)
- 비동기·장기 실행 쿼리 관리(큐·취소), 결과 캐시
- JOIN kind 판정(spec 004의 joins로 이미 커버), 멀티 벤더(④)

## 3. 재작성 엔진 (SqlRewriter)

입력: 원본 SQL + IR + 카탈로그 매핑 + purposeCode. 출력: `RewriteResult(sql, applied: List<AppliedRewrite>)`.

| kind | 재작성 | 예 |
|---|---|---|
| MASK | 해당 컬럼이 select-item으로 조회되면 **강제식으로 치환** + 원래 별칭 유지 | `SELECT email` → `SELECT mask_email(email) AS email` |
| FILTER | 술어를 **최상위 AND conjunct로 주입**(이미 있으면 생략) | `WHERE …` → `WHERE … AND consent_yn = 'Y'` |
| INTEGRITY | 동일하게 WHERE 주입 | `AND col IS NOT NULL` |
| LIMIT | 없으면 상한 주입, 상한 초과면 **하향 조정** | `LIMIT 5000` → `LIMIT 1000` |
| BLOCK/PARTITION | **재작성하지 않는다** — 판정(차단·요구)이 본질 | — |

원칙:
- 재작성은 **Druid AST를 조작해 출력**한다(문자열 조립 금지) — 주입 SQL이 문법적으로 안전해야 한다.
- **모든 스코프에 적용**(spec 001 §6.2와 대칭): 서브쿼리·CTE·UNION 팔 안의 MASK 컬럼·FILTER 대상 테이블도 재작성.
- **재작성 실패 = 실행 차단**(fail-closed). 부분 적용 금지 — 하나라도 적용 불가면 전체 실패.
- `SELECT *`는 no-select-star가 이미 BLOCK이므로 마스킹 누락 경로가 없다(spec 002 §3.2 의존 유지).
- 재작성 결과는 사용자에게 **그대로 노출**한다(무엇이 적용됐는지 감출 이유 없음, 감사에도 기록).

### 3.1 must_be_masked 판정 (spec 004 잔여)
MASK 매핑된 컬럼이 select-item에 **원본으로** 등장하면 위반. 단 자동 재작성이 기본이므로
실무 흐름에서는 재작성이 먼저 적용돼 위반이 발생하지 않는다 → **판정은 "재작성을 끈 저장 경로"에서만 의미**를 갖는다.
따라서 저장(lint) 시에는 **WARN**("실행 시 자동 마스킹됩니다")으로, 실행 시에는 재작성으로 처리한다.

## 4. 실행 (동일 DB 전제)

- **데모 데이터**: 설정 DB에 `demo_users`·`demo_marketing_consents`·`demo_user_events` 같은 실제 데이터 테이블을
  시드로 만든다. 카탈로그의 논리 테이블명(`users`)과 물리 데모 테이블명을 **매핑 테이블**(`demo_table_map`)로 연결하고,
  실행 시에만 치환한다 — 카탈로그·룰·권한은 논리명을 그대로 쓴다(모델 오염 방지).
- **읽기 전용 연결**: 실행 전용 `DataSource`(별도 빈, `readOnly=true` 트랜잭션 + `SET SESSION TRANSACTION READ ONLY`).
  SELECT 외 문은 파서 게이트가 이미 막지만 **이중 방어**.
- **행 상한·타임아웃**: `guardian.exec.max-rows`(기본 1000), `guardian.exec.timeout-ms`(기본 5000).
  `Statement.setMaxRows`·`setQueryTimeout` + 초과 시 명확한 오류 메시지.
- 결과: 컬럼 메타 + 행 배열 + `rowCount`·`elapsedMs`·`appliedRewrites`·`truncated:Boolean`.

## 5. 실행 게이트 (순서)

```
인증(401) → 데이터 권한(403) → 룰 판정(422, BLOCK이면 실행 불가)
→ 검토 승인 확인(403 NOT_REVIEWED) → 재작성(실패 시 500 대신 422) → 실행
```
- **검토 상태 게이트**(사용자 확정): `review_status = APPROVED`인 **저장된 쿼리만** 실행 가능.
  즉 실행은 에디터의 임의 SQL이 아니라 **저장·검토 승인된 쿼리 실행**이다(`POST /api/queries/{id}/execute`).
  → spec 005 §2의 "review_status는 표시용" 한계가 **여기서 게이트로 승격**된다.
- 에디터의 임의 SQL 실행은 **비범위**(디자인의 실행 버튼은 "저장·승인된 쿼리 실행"으로 의미를 좁힌다).
  저장 전 미리보기가 필요하면 **재작성 결과만 보여주는 `POST /api/preview-rewrite`**(실행 없음)를 제공한다.

## 6. 감사 (append-only)

```
execution_event(id, query_id, actor, original_sql, rewritten_sql, applied_json,
                row_count, elapsed_ms, truncated, outcome[SUCCESS|BLOCKED|ERROR], error_message, at)
```
- BLOCKED(게이트 차단)·ERROR(타임아웃·SQL 오류)도 기록한다 — 실행 시도 자체가 감사 대상.
- 원본·재작성 SQL을 모두 남겨 "무엇이 자동 적용됐는지"를 사후 검증할 수 있게 한다.

## 7. API

```
POST /api/queries/{id}/execute        → 200 ExecutionResultDto | 403(권한·미검토) | 422(룰·재작성 실패)
POST /api/preview-rewrite {sql, requestId?} → 200 {rewrittenSql, applied[]} (실행 없음, 저장 전 확인용)
GET  /api/queries/{id}/executions     → [ExecutionEventDto] (본인·STEWARD/ADMIN)
```
```
ExecutionResultDto {
  columns: [{name, type}], rows: [[Any?]],
  rowCount, elapsedMs, truncated,
  rewrittenSql, applied: [{kind, table, column, detail}]
}
```

## 8. 프론트엔드

- **에디터 실행 결과 탭**(현재 스텁 → 실): 저장·검토 승인된 쿼리를 선택했을 때만 실행 활성.
  결과 그리드(마스킹된 실제 값) + "N rows · Xms · LIMIT 적용됨" + **적용된 재작성 목록**(무엇이 자동 적용됐는지) +
  재작성 SQL 보기(토글). 미검토 쿼리는 "검토 승인 후 실행할 수 있습니다" 안내.
- **저장된 쿼리 화면**: 검토 승인 행에 "실행" 액션, 상세에 최근 실행 이력.
- 실행 버튼의 스텁 메시지(spec 003 §4-2) 제거 — 이제 실제 동작.

## 9. 검증 기준

- [ ] 기존 102 테스트 회귀
- [ ] **재작성 단위**: MASK 치환(별칭 유지) / FILTER 주입(중복 시 생략) / LIMIT 하향 / INTEGRITY 주입 /
      서브쿼리·CTE·UNION 팔 내부까지 적용 / BLOCK·PARTITION은 재작성 안 함
- [ ] **재작성 실패 = 차단**: 표현 불가 강제식·파싱 실패 시 실행 거부(부분 적용 없음)
- [ ] **실행 게이트**: 미검토 쿼리 실행 → 403 NOT_REVIEWED / 권한 없는 테이블 → 403 / 룰 BLOCK → 422
- [ ] **실행 결과가 실제로 마스킹**됨: `email`이 `j***@naver.com` 형태로 반환(원본 유출 없음)
- [ ] 행 상한: 상한 초과 데이터에서 `truncated=true`·행 수 제한 / 타임아웃 시 명확한 오류
- [ ] 읽기 전용: 실행 연결에서 INSERT/UPDATE 시도 시 실패(이중 방어 확인)
- [ ] 감사: SUCCESS·BLOCKED·ERROR 모두 `execution_event`에 원본+재작성 SQL과 함께 기록
- [ ] must_be_masked: 저장 시 WARN("실행 시 자동 마스킹")로 표시, 실행에서는 재작성 적용
- [ ] 화면: 실행 결과·적용된 재작성·재작성 SQL 토글·미검토 안내 (스크린샷)

## 10. 마일스톤

1. **M1**: SqlRewriter(AST 조작·전 스코프) + 단위 스위트 + `preview-rewrite` API
2. **M2**: 실행 인프라(읽기 전용 DataSource·상한·타임아웃) + 데모 데이터·매핑 + 실행 게이트 + 감사 + execute API
3. **M3**: 에디터 실행 결과·재작성 표시 + 저장쿼리 실행 액션·이력 + E2E·화면 대조

## 11. 결정 기록 (2026-07-25 사용자)

1. **동일 DB로 시작** — 설정 DB에 데모 데이터 테이블. 원격 대상 DB·자격증명은 ④에서 재검토.
2. **자동 재작성** — MASK 치환·FILTER 주입을 서버가 수행. 디자인의 마스킹 결과가 실제로 동작.
3. **안전장치 4종 전부** — 검토 상태 게이트 · 행 상한·타임아웃 · 읽기 전용 연결 · 실행 감사 로그.
4. (파생·AI) 실행 대상은 **저장·검토 승인된 쿼리**로 좁힌다(임의 SQL 실행 비범위). 저장 전에는 `preview-rewrite`.
5. (파생·AI) 논리 테이블명 ↔ 데모 물리 테이블 매핑은 **실행 시점에만** 치환(카탈로그·룰·권한 모델 오염 방지).
6. (파생·AI) 재작성 실패는 fail-closed(부분 적용 금지). must_be_masked는 저장 시 WARN·실행 시 재작성.
