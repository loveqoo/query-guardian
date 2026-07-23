# Learning INDEX

- 001 멀티 방언 SQL 파싱 전략 — 업계 수렴 답은 "방언별 정밀 파서 + 룰 전용 얇은 IR"; MySQL 1호는 Alibaba Druid 파서(WallFilter 선례), ANTLR는 CST만 줘서 AST 층 비용 큼 [파서, ANTLR, Druid, JSQLParser, Calcite, IR, 거버넌스]
- 002 스펙 001 적대 검토 — 게이트 스펙의 본체는 룰 목록이 아니라 fail-closed 판정 계약; 우회 9축 체크리스트(OR 가지·스코프 은닉·UNION·멀티문·문 종류·오탐·alias·Raw 기본값·purpose 자가 면제) [적대 검토, fail-closed, 우회, 룰 계약]
- 003 M1 코드 적대 검증 — 계약 테스트로는 파서 "표현" 결함(백틱 보존·alias 소실)을 못 잡는다; 새 파서 어댑터마다 식별자 표현 프로브 필수, 검증자에겐 "실제 jar로 확인" 요구 [검증, 백틱, 정규화, 셀프 조인, 인스턴스 귀속, fail-closed]
