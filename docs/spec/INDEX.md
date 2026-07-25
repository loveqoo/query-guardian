# Spec INDEX

- 001 MVP 범위·아키텍처 — MySQL+Druid 파서+재귀 IR+fail-closed 룰 계약(§6)+룰 4종+CodeMirror+저장 게이트; SELECT만 저장, 관리형 purpose; M1~M4 [MVP, 아키텍처, IR, Druid, 룰 계약, fail-closed, CodeMirror] (v2 승인)
- 002 제약 카탈로그 개편 — 컬럼 클래스 6종×kind 6종 사전→컬럼 매핑→룰 어휘 통제(디자인 1단계); 판정만(재작성 유보), IR 컬럼 참조 수집 필수(C1)+block 판정+복합 파티션+LIMIT 상한; 결정 7건 기록 [제약, 카탈로그, kind, PII, 클래스, columnRefs, 디자인] (v2 승인)
- 003 디자인 UI 전면 컨버팅 — dc.html이 유일 원본, 기존 UI 제거+셸(4그룹 7메뉴)+화면 7종 antd 컨버팅; A(실 로직 3)·B(부분 1)·C(스텁 3) 등급, 스텁=디자인 샘플 데이터+조용한 무시 금지, 백엔드 무변경 [디자인, 컨버팅, 셸, 스텁, antd] (구현 완료)
- 004 규칙 데이터화 — 사용자 정의 규칙(scope·AND/OR 트리·조건 단위 severity)+엔진, 조건 op requires·blocks·joins 판정(within·masked는 등록만+미판정 배지), IR 조인 등식 확장, RulesPage 실 연결; 하이브리드(시스템 룰 유지) [규칙, 엔진, op, 트리, severity, joinEqualities, 어휘 통제] (구현 완료)
- 005 승인 요청·쿼리 검토 워크플로 — 승인 후 쿼리 작성(모델 교정); 요청(순차 승인·행위자 스텁·실 규칙 참조)+저장 게이트 확장(승인된 요청 필수·테이블 커버)+쿼리 검토 상태; 화면 3종 실 연결 [승인, 검토, 워크플로, 순차 승인, 게이트 확장, purposeCode 승계, 테이블 커버] (구현 완료)
