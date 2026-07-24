# Learning INDEX

- 001 멀티 방언 SQL 파싱 전략 — 업계 수렴 답은 "방언별 정밀 파서 + 룰 전용 얇은 IR"; MySQL 1호는 Alibaba Druid 파서(WallFilter 선례), ANTLR는 CST만 줘서 AST 층 비용 큼 [파서, ANTLR, Druid, JSQLParser, Calcite, IR, 거버넌스]
- 002 스펙 001 적대 검토 — 게이트 스펙의 본체는 룰 목록이 아니라 fail-closed 판정 계약; 우회 9축 체크리스트(OR 가지·스코프 은닉·UNION·멀티문·문 종류·오탐·alias·Raw 기본값·purpose 자가 면제) [적대 검토, fail-closed, 우회, 룰 계약]
- 003 M1 코드 적대 검증 — 계약 테스트로는 파서 "표현" 결함(백틱 보존·alias 소실)을 못 잡는다; 새 파서 어댑터마다 식별자 표현 프로브 필수, 검증자에겐 "실제 jar로 확인" 요구 [검증, 백틱, 정규화, 셀프 조인, 인스턴스 귀속, fail-closed]
- 004 디자인 산출물 분석 — 목표 상태 청사진: 컬럼 클래스×kind 5종 제약 사전→컬럼 매핑→규칙이 그 어휘만 참조(2계층 통제)+승인·실행·마스킹·권한; gap B1~B14, 사람 결정 5건(판정vs재작성, severity, 파티션, 규칙 선택 fail-closed, 승인 게이트) [디자인, 제약 카탈로그, 규칙 빌더, 승인, 마스킹, gap]
- 005 spec 002 구현 — MySQL UNIQUE는 NULL 비중복(앱 검사 이중화 필수); columnRefs를 술어 모델과 분리하니 한 visitor로 전 절 커버; DTO 전문 위임=통합 수정 0건; 검토엔 "스펙 vs 구현 코드 대조" 고정 [UNIQUE NULL, columnRefs, 위임 계약, 검토 축]
