# 004 — 규칙 데이터화: 사용자 정의 규칙 엔진 + 규칙 관리 화면 연결

> 상태: **구현 완료 (v2 — 적대 검토 반영)**
> 작성: 2026-07-25 · 근거: `.dev/learning/004-design-deliverable-analysis.md`(규칙 메타모델), spec 002(제약 어휘), spec 001 §6(fail-closed 계약)
> v2 변경: joinEqualities 수집을 §6.1과 동일 규칙으로 한정(C1·C2), joins 조건에 refTable/refColumn 명시(H1),
> deferred 조건 트리 시맨틱·미강제 규칙 상태(C3), 매핑 삭제 역참조 가드·dangling 정책(C4), requires 판정가능성 재검사(H3),
> OR 그룹 severity·세탁 수용 명시(H2·H4), 전역 배지 정정(H5), 손상 규칙 격리(H6), 통계 기준 정정(H7).

## 1. 목표

디자인의 **사용자 정의 규칙**을 데이터로 만든다: 이름·범위·AND/OR 조건 트리를 가진 규칙을 등록하고,
각 조건이 **카탈로그에 매핑된 제약만 참조**(spec 002의 2계층 어휘 통제)하며, 규칙 엔진이 쿼리 IR에
비추어 판정한다. spec 003에서 만든 규칙 관리 3단 화면을 이 API에 연결한다.

## 2. 범위 / 비범위 (사용자 결정 반영)

### 범위
- **규칙 엔티티**: `{name, scope, server, enabled, tree}` — 트리는 JSON 저장.
- **조건 단위 severity** (결정 4): 각 조건이 BLOCK/WARN 개별 보유. 규칙 severity는 파생 요약(미충족 leaf 최댓값).
- **판정 op 3종** (결정 2): `requires`·`blocks`·`joins` 실판정. `must_be_within`·`must_be_masked`는 등록·표시만.
- **IR 확장**: 조인 등식(`joinEqualities`) 수집 — joins 판정의 전제(§6.1 규칙과 동일하게 한정).
- **하이브리드 룰 구조** (결정 1): 기존 시스템 룰 유지 + 사용자 정의 규칙 계층 추가. 합성은 **순수 union**.
- **규칙 CRUD API** + 규칙 관리 화면 실 연결(spec 003 RulesPage).
- **위반 통계**: 저장 시도에서 규칙 위반 탐지 시 규칙별 카운트(BLOCK/WARN 무관, §7 정정).
- **참조 무결성**: 매핑 삭제가 규칙 조건을 고아로 만들지 않게 가드(§7).

### 비범위 (후속)
- `must_be_within`·`must_be_masked` **판정** — 등록·표시만.
- 멀티 서버 실 라우팅(server는 저장·표시·필터용 문자열).
- 승인·권한·실행·AI·쿼리 상태. 규칙 "테스트 실행" 실동작(버튼은 스텁).

## 3. 규칙 모델

### 3.1 엔티티·트리
```
rule(id, name, scope[SINGLE|MULTI|GLOBAL], server VARCHAR NULL, enabled BOOLEAN, tree_json TEXT)
```
```
Group     = { combinator: "all"|"any", children: [Group | Condition] }   // 빈 그룹 금지
Condition = {
  op: "requires"|"blocks"|"joins"|"must_be_within"|"must_be_masked",
  severity: "BLOCK"|"WARN",                 // 조건 단위 (결정 4)
  // 컬럼 기반(single/multi):
  table?, column?, defId?,                  // defId = 이 컬럼에 매핑된 constraint_def
  mappingId?,                               // 다중 purpose 매핑 구분 (M2) — requires의 술어·params 출처
  // joins 전용 (H1):
  refTable?, refColumn?,                    // 조인 상대 엔드포인트 (":ref_table.id" 템플릿 폐기)
  // 전역(global):
  subject?, value?,
}
```
등록 검증(§7): defId가 해당 컬럼에 실제 매핑, 빈 그룹 거부, op·severity enum 검증,
**op=requires면 매핑 술어의 `requiredForm` 판정 가능성 재검사**(EQ-리터럴/IN-단일이 아니면 400 — 미지원 형태가
테이블 전면 차단을 유발하는 spec 002 C2 부활 방지, H3). must_be_* 조건은 `judged=false` 플래그로 저장.

### 3.2 scope와 적용 대상
- **SINGLE**: 조건 table 1개. 쿼리가 그 테이블을 참조하는 스코프마다 적용.
- **MULTI**: 조건이 2+ 테이블. **쿼리가 규칙 대상 테이블 중 하나라도 참조하면 적용**. joins 조건은 대상
  테이블들이 **동시에 스코프에 존재**할 때만 충족 가능(한 테이블만 있으면 미충족 → fail-closed, M3).
- **GLOBAL**: 전역 조건은 free-text subject라 컬럼 op로 판정 불가 → **이번 스펙 등록·표시만**. 화면 배지
  **"이 규칙은 이번 버전에서 강제되지 않습니다 (표시 전용)"**(H5 — "시스템 룰이 담당" 문구 삭제, 오도 방지).
- server: 저장·표시·필터용. 판정은 단일 DB. 미지정(global) 허용.

## 4. 판정 계약 (spec 001 §6 상속 + op별 의미)

규칙은 **활성**일 때 적용 대상 스코프마다 트리를 평가한다.

### 4.1 트리 평가 + deferred 처리 (C3)
- `all`: 모든 판정 대상 자식 충족 시 그룹 충족. `any`: 하나라도 충족 시 그룹 충족.
- **deferred 조건(must_be_*, judged=false)은 만족/불만족 어느 쪽에도 계상하지 않고 severity 집계에서도 제외.**
- **판정 가능 조건(requires/blocks/joins)이 재귀적으로 0개인 규칙/그룹 = 규칙-레벨 "미강제(표시 전용)" 상태.**
  목록 카드·상세에 "이 규칙은 아무것도 차단/경고하지 않습니다"를 표기(배지만으로 부족 — enabled/severity가
  강제되는 것처럼 보이는 문제 차단). 플래그십 r1 "PII 마스킹 필수"는 이번 스펙에서 이 상태다.
- 위반 발생 = 규칙 트리 불충족. 위반의 severity:
  - `all` 그룹: 미충족 leaf 조건 각각을 그 조건의 severity로 보고.
  - `any` 그룹: **모든 판정 자식이 미충족일 때만** 위반. 그룹 대표 severity = **미충족 leaf(deferred 제외) 최댓값**(재귀).
- **OR-세탁 수용 (H2·H4, 결정 기록)**: `any` 그룹에서 약한 WARN 팔을 만족해 BLOCK 팔을 탈출하는 것은
  **사용자가 OR를 저작한 의도로 간주해 수용**한다. 단 빌더는 `any`가 서로 다른 severity를 섞으면 **경고 표시**.
  (시스템 룰과 §6.1은 무관하게 그대로 fail-closed 유지 — 이 수용은 사용자 규칙 트리 내부에 한정.)

### 4.2 op별 "충족" 정의 (fail-closed — 확인 불가는 미충족)
- **requires(table.column, defId/mappingId)**: 매핑된 FILTER 술어가 스코프 `whereConjuncts`에 **최상위 AND
  conjunct**로 존재(§6.1·§6.5 재사용). 인스턴스 귀속·Raw 미충족 상속. purpose 조건부 매핑이면 매핑의
  purpose가 현재 컨텍스트와 일치할 때만 요구(불일치 시 조건 비적용, M6).
- **blocks(table.column, defId)**: 컬럼이 스코프 `columnRefs`에 참조되면 위반(§5.2 no-blocked-column 로직·귀속 재사용).
- **joins(table.column ↔ refTable.refColumn)**: IR `joinEqualities`에 `{table.instanceKey.column = refTable.instanceKey.refColumn}`
  등식이 **양변 모두 물리 테이블로 귀속되어** 존재해야 충족(방향 무관). 한쪽이라도 비귀속(table=null)이거나
  엔드포인트 불일치면 미충족(H1). **직접 등식만** 인정(이행 조인 A-B-C는 A-C 직접 등식 없으면 미충족, M5).
  joins는 **조인 위상만** 검증 — 행 필터가 아니다. 값 조건(동의='Y')은 같은 `all` 그룹에 requires 병기 필요(H4).
  표준 형태 예: `all[ joins(mc.user_id ↔ users.id), requires(mc.consent_yn = 'Y') ]`.
- **must_be_within / must_be_masked**: 이번 스펙 판정 미구현(§2). 위반 미발생 + 화면 "판정 미구현" 배지.

## 5. IR 확장 — 조인 등식 (joins 전제, §6.1과 동일 규칙 — C1·C2)

`SelectScope`에 `joinEqualities: List<ColumnEquality>` 추가. `ColumnEquality(left: ColumnRef, right: ColumnRef)`
— 양변 모두 컬럼으로 귀속된 `=`만. 수집 위치는 **§6.1과 정확히 동일**하게 한정한다:
- **INNER 계열 JOIN ON + WHERE의 최상위 AND conjunct에서만** 수집(`flattenAnd` top-level 경로).
- **OUTER JOIN ON 제외**(LEFT/RIGHT는 null-producing 쪽을 살려 필터를 무력화 — §6.1이 이미 배제, C1).
- **Or/Not 하위 제외**(`toPredicate`의 OR 재귀·`toComparison`의 OR 경유에서 수집 금지 — OR-세탁 방지, C2).
구현: `flattenAnd`가 최상위 conjunct를 분해할 때, 양변 컬럼인 `=` Comparison을 Predicate.Raw로 떨구는
대신 `ColumnEquality`로도 수집한다(술어 모델에는 계속 Raw로 남겨 기존 계약 불변). 방향 무관(a=b ≡ b=a).
`USING(col)`·`NATURAL JOIN`은 `source.condition`이 없어 등식 미수집 → joins **미충족(fail-closed)**, 문서화(M1).
서브쿼리 경계에서 멈춘다. 셀프 조인은 instanceKey를 물리 테이블명으로 환원해 매치(L4).

## 6. 백엔드

- `RuleRepository`(spring-data-jdbc), `Rule` 엔티티, `tree_json` = Jackson 직렬화 `RuleTree`.
- `UserRuleEvaluator`: 활성 규칙 로드 → 스코프별 트리 평가 → `List<Violation>`(조건 severity, ruleId=`rule/{id}`).
- `RuleEngine` 확장: 기존 시스템 룰 + `UserRuleEvaluator` **순수 union**(사용자 위반이 시스템 위반을 억제 불가,
  BLOCK은 어느 계층에서 와도 차단 — L3). §6 계약·게이트 가드 무변경.
- **손상 규칙 격리 (H6)**: tree_json 역직렬화 실패·미지 op 로드 시 해당 규칙만 격리(다른 규칙·시스템 룰 불변),
  규칙을 "손상 — 비활성" 상태로 표시(조용한 스킵 금지). 미지 op/severity는 등록 시 400.
- **dangling defId (C4)**: 조건의 defId가 그 컬럼에 더는 매핑 안 되면 평가기는 **fail-closed 위반**(크래시·스킵 금지)
  + 화면 "참조 깨진 조건" 표기.
- 입력 가드: tree 최대 깊이·노드 수 제한(L2). hit_count는 원자적 증가(L1).

## 7. API·무결성
```
GET    /api/rules                 → [RuleDto] (name,scope,server,severity 요약,hits,enabled,enforced:boolean)
GET    /api/rules/{id}            → RuleDetailDto (tree 포함, 각 조건 judged 플래그·dangling 플래그)
POST   /api/rules {name,scope,server?,tree} → 201 | 400(매핑 안 된 defId·빈 그룹·requires 판정불가·op/severity 오류)
PUT    /api/rules/{id}            → 200 | 400
DELETE /api/rules/{id}            → 204
POST   /api/rules/{id}/test {sql} → 200 {message:"테스트 실행은 후속 스펙에서 구현됩니다"} (화면 버튼 스텁)
```
- **매핑 삭제 역참조 가드 (C4)**: `DELETE /api/catalog/mappings/{id}` — 그 (defId, columnId)를 참조하는 규칙
  조건이 있으면 **409**(먼저 규칙 조건 제거 요구). def 삭제 가드(spec 002 H5)와 대칭.
- **위반 통계 (H7)**: `createQuery/updateQuery` 저장 시도 시 규칙 위반 탐지분(**BLOCK·WARN 무관**)의 규칙
  hit_count 증가(debounce lint 미포함). 라벨은 "위반 감지 N회"(WARN 규칙도 카운트되게). 저장 성공/실패 무관, 시도 기준.
- 빌더 조건 편집(테이블→컬럼→매핑된 제약)은 `/api/catalog/mappings`(컬럼 필터)·`/defs`·`/schema` 재사용.

## 8. 프론트엔드 (RulesPage 실 연결)

spec 003 RulesPage(로컬 스텁)를 실 API로 전환:
- 목록·빌더·IR 트리 UI 유지, 데이터 소스 `/api/rules`.
- 조건 편집 "제약 조건 select = 매핑된 제약만"을 `/api/catalog/mappings`로 실데이터화. joins 조건은 refTable/refColumn 선택 UI.
- must_be_within/must_be_masked 조건에 "판정 미구현" 배지. **미강제 규칙(판정 조건 0개)은 카드에 "강제 안 함" 표기**(C3).
  전역 규칙 배지 "표시 전용"(H5). dangling·손상 조건은 경고색 표기.
- 저장/삭제/추가 실 API. 테스트 실행은 스텁 메시지. severity 요약·"위반 감지 N회"는 API 값.

## 9. 검증 기준

- [x] spec 001 §12 + spec 002 §7 스위트 전부 회귀(시스템 룰·게이트 무변경)
- [x] **requires**: 매핑 FILTER 술어 누락 시 조건 severity대로 차단/경고, 충족 시 통과 (E2E)
- [x] **blocks**: 대상 컬럼 참조 시 차단(함수 인자·GROUP BY 등 columnRefs 전 범위), 미참조 통과
- [x] **joins 우회 스위트 — 전부 미충족(위반)**: LEFT/RIGHT JOIN ON 등식(C1) / `ON (a=b OR 1=1)`(C2) /
      엉뚱한 컬럼 조인 `mc.user_id=u.other`(H1) / 대상 테이블 하나만 존재 / USING·NATURAL(M1) / 이행 조인(M5)
- [x] **joins 정탐 — 통과**: `INNER JOIN mc ON mc.user_id=u.id`(방향 무관·alias) + requires 병기 표준형
- [x] **조건 단위 severity**: 한 규칙에 BLOCK+WARN 조건 → 각각 분리 보고
- [x] **deferred/미강제**: must_be_* 전용 규칙은 위반 미발생 + "강제 안 함" 표기; deferred는 트리 평가·severity에서 제외
- [x] **어휘 통제·판정가능성**: 매핑 안 된 defId 400 / requires가 `<>`·함수형 참조 시 400(H3) / 빈 그룹 400
- [x] **AND/OR**: any는 한 팔만 충족해도 통과, all은 전부 필요; 중첩 severity 재귀 정확
- [x] **무결성**: 규칙이 참조 중인 (def,컬럼) 매핑 삭제 409 / dangling 조건은 fail-closed 위반+표기 / 손상 tree_json 격리
- [x] **통계**: 저장 시도 위반 시 hit 증가(WARN 포함), debounce 미증가
- [x] 화면: 목록·빌더·조건 편집(매핑 실데이터·refTable 선택)·트리·저장/삭제 실동작 (스크린샷 대조)

## 10. 마일스톤

1. **M1**: IR joinEqualities(§5) + Rule 엔티티·RuleTree·UserRuleEvaluator + 판정 3종 + 조건 severity·deferred·미강제 + 회귀·joins 우회 스위트
2. **M2**: RuleService(등록 검증·판정가능성 재검사·무결성 가드·통계) + CRUD API + 매핑 삭제 가드 + test 스텁
3. **M3**: RulesPage 실 연결 + 배지(미판정·미강제·전역·dangling) + E2E·화면 대조

## 11. 결정 기록

1~4. (2026-07-25 사용자) 하이브리드 / 판정 op 3종 / 한 스펙에 전부 / 조건 단위 severity.
5. (파생) 전역 사용자 규칙은 등록·표시만, 배지 "표시 전용"(H5).
6. (파생·AI) joinEqualities는 §6.1과 동일 한정(INNER ON+top-level AND, OUTER·OR 제외) — C1·C2.
7. (파생·AI) joins 조건에 refTable/refColumn 명시, 양변 물리 귀속 완전 매치, 직접 등식만 — H1·M5. joins는 위상만, requires 병기 표준형 — H4.
8. (파생·AI) deferred는 트리·severity에서 제외, 판정 조건 0개 규칙은 "미강제(표시 전용)" 규칙-레벨 상태 — C3.
9. (파생·AI) 매핑 삭제 역참조 가드(409) + dangling fail-closed + 손상 규칙 격리 — C4·H6.
10. (파생·AI) requires 등록 시 판정가능성(requiredForm) 재검사 — H3.
11. (수용·AI) 사용자 규칙 `any` 내부 OR-세탁은 저작 의도로 수용(빌더 경고), 시스템 룰·§6.1은 불변 — H2·H4.
12. (파생·AI) 통계는 저장 시도 위반 탐지 기준(BLOCK/WARN 무관), 라벨 "위반 감지 N회" — H7.
