# 008 — 규칙 데이터화(spec 004) 구현에서 배운 것

> 2026-07-25. 사용자 정의 규칙 엔진·판정 3종·화면 연결.

1. **IR 축을 판정 목적별로 분리하니 새 판정이 기존 계약을 안 건드린다** — whereConjuncts(형태 판정)·
   columnRefs(참조 사실)·joinEqualities(조인 위상)를 각각 독립 축으로 두자, joins 판정이 파서의 기존
   술어 모델을 전혀 안 건드리고 추가됐다. joinEqualities 수집을 `flattenAnd` **최상위 conjunct 경로**에
   꽂은 것이 핵심 — OUTER ON은 애초에 그 경로에 안 오고(§6.1이 이미 배제), OR 하위는 joinEqs=null로
   호출돼 자동 제외. 즉 **적대 검토 C1(OUTER)·C2(OR-세탁)가 "수집 위치"만으로 동시에 막혔다.**
   교훈: 새 판정을 추가할 땐 기존 fail-closed 경계(여기선 §6.1의 top-level-AND)에 **얹으면** 계약이 공짜로 상속된다.

2. **enum ordinal ≠ 심각도** — `Severity {BLOCK, WARN}`에서 `maxOf`는 ordinal 기준이라 WARN을 최댓값으로
   잡아, OR 그룹 대표 severity가 BLOCK인데 WARN으로 보고될 뻔했다(게이트가 안 막힘). `worstSeverity` 헬퍼로
   교정. 코드 작성 중 잡았지만, **enum에 순서 의미를 부여할 땐 명시 비교 함수**를 두는 게 안전.

3. **"미강제" 상태는 배지가 아니라 규칙-레벨 플래그여야 한다(C3)** — must_be_masked 전용 규칙은 enabled·
   severity=BLOCK로 보이지만 아무것도 강제 안 한다. `enforced` 파생 플래그(판정 조건 재귀 0개)를 DTO에
   넣고 화면 카드에 "강제 안 함"을 띄우니, 사용자가 마스킹이 강제된다고 오인하는 게이트 신뢰 구멍이 닫혔다.
   화면에서 실제로 "강제 안 함" 배지가 뜨는 것을 스크린샷으로 확인.

4. **Spring 빈 순환 주의** — QueryService→RuleService(통계), CatalogService→RuleService(매핑 삭제 가드),
   RuleEngine→UserRuleEvaluator→(람다)RuleService. RuleService가 CatalogService/QueryService를 역참조하지
   않게 **repos·TableCatalog만 의존**시켜 순환을 피했다. 통합 테스트의 컨텍스트 로드가 순환 검증을 겸한다.

5. **서브에이전트 세션 한도 중단 대응** — M3 프론트 에이전트가 빌드 검증 직전 세션 한도로 실패했으나
   코드는 완성돼 있었다(빌드 통과). **위임이 중단돼도 산출물이 파일에 남으면 메인이 이어받아 검증만
   하면 된다** — 위임을 "자기완결 파일 출력"으로 설계한 것이 복구를 싸게 만들었다.

## 남은 커버리지 갭 (다음에 보강)

- requires의 "매핑됐으나 판정 불가 형태(`<>`·함수형)" 400 케이스는 코드(H3)는 있으나 전용 테스트 미작성
  (unmapped defId 400만 테스트됨). 다음 라운드에 추가.
- 손상 tree_json 격리(H6)·dangling defId fail-closed(C4 평가기 경로)의 전용 테스트도 코드만 있고 미검증.
