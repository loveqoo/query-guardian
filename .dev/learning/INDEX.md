# Learning INDEX

- 001 멀티 방언 SQL 파싱 전략 — 업계 수렴 답은 "방언별 정밀 파서 + 룰 전용 얇은 IR"; MySQL 1호는 Alibaba Druid 파서(WallFilter 선례), ANTLR는 CST만 줘서 AST 층 비용 큼 [파서, ANTLR, Druid, JSQLParser, Calcite, IR, 거버넌스]
- 002 스펙 001 적대 검토 — 게이트 스펙의 본체는 룰 목록이 아니라 fail-closed 판정 계약; 우회 9축 체크리스트(OR 가지·스코프 은닉·UNION·멀티문·문 종류·오탐·alias·Raw 기본값·purpose 자가 면제) [적대 검토, fail-closed, 우회, 룰 계약]
- 003 M1 코드 적대 검증 — 계약 테스트로는 파서 "표현" 결함(백틱 보존·alias 소실)을 못 잡는다; 새 파서 어댑터마다 식별자 표현 프로브 필수, 검증자에겐 "실제 jar로 확인" 요구 [검증, 백틱, 정규화, 셀프 조인, 인스턴스 귀속, fail-closed]
- 004 디자인 산출물 분석 — 목표 상태 청사진: 컬럼 클래스×kind 5종 제약 사전→컬럼 매핑→규칙이 그 어휘만 참조(2계층 통제)+승인·실행·마스킹·권한; gap B1~B14, 사람 결정 5건(판정vs재작성, severity, 파티션, 규칙 선택 fail-closed, 승인 게이트) [디자인, 제약 카탈로그, 규칙 빌더, 승인, 마스킹, gap]
- 005 spec 002 구현 — MySQL UNIQUE는 NULL 비중복(앱 검사 이중화 필수); columnRefs를 술어 모델과 분리하니 한 visitor로 전 절 커버; DTO 전문 위임=통합 수정 0건; 검토엔 "스펙 vs 구현 코드 대조" 고정 [UNIQUE NULL, columnRefs, 위임 계약, 검토 축]
- 006 디자인 활용 실패 — 모델 우선·셸 5순위 순서는 합의됐어도 사용자 기대는 "내 디자인이 보이는 것"; 디자인 산출물엔 UI 컨버팅 선행이 기본값, 로직 없는 화면은 샘플 데이터 스텁으로라도 화면 먼저 일치 [디자인 충실도, UI 우선, 기대 관리]
- 007 디자인 컨버팅 실행 — 원본이 antd 앱이면 "이식"이라 쉬움(먼저 통독 확인); 기반 직렬+화면 병렬=충돌 0; 내 브리프의 토큰 오지시(pending blue)가 전 화면 전파 → 위임 전 검토와 토큰 교차검증 [컨버팅, 병렬 위임, 토큰, 공유 상수]
- 008 규칙 데이터화 구현 — IR 축을 판정별 분리(joinEqualities를 §6.1 top-level에 얹어 OUTER·OR 우회 위치만으로 차단); enum ordinal≠심각도(worstSeverity); 미강제는 배지 아닌 규칙 플래그(enforced); 위임은 파일 완결로 중단에 강하게 [규칙 엔진, joinEqualities, severity, enforced, 빈 순환]
- 009 승인·검토 구현 — 게이트 추가가 기존 게이트를 열 수 있다(purposeCode 유실→승인 요청에서 서버 주입으로 승격); "IR 대상 테이블"은 집합 정의를 식으로(CTE 우회); HTTP 헤더는 ASCII(actor id화가 검증도 강화); 스텁 identity는 한계 명문화가 기능 [purposeCode, 테이블 커버, actor, 검토 리셋, 재-lint]
