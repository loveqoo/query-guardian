# Query Guardian

통제된 환경에서 안전하게 SQL을 작성·등록하는 **SQL 거버넌스 서비스**.
사용자가 에디터에서 SQL을 작성하면 파싱(AST)→IR→룰 검사를 거쳐, 룰 위반 시 저장을 차단하고 사유를 알려준다.

- 스펙: `docs/spec/001-mvp-scope.md` (룰 판정 계약은 §6)
- 아키텍처 요지: 방언별 정밀 파서(MySQL=Alibaba Druid) → 얇은 자체 IR → fail-closed 룰 엔진 + 메타데이터 카탈로그

## 실행

```bash
# 1) 설정 DB (MySQL 8, 포트 3307)
cd docker && docker compose up -d

# 2) 백엔드 (Spring Boot, 포트 8080)
cd backend && ./gradlew bootRun

# 3) 프론트엔드 (Vite, 포트 5173 — /api를 8080으로 프록시)
cd frontend && npm install && npm run dev
```

http://localhost:5173 접속 → 카탈로그에서 테이블·제약·purpose 등록 → 에디터에서 쿼리 작성.

## 테스트

```bash
cd backend && ./gradlew test    # 단위 + 우회/오탐 스위트 + Testcontainers 통합(docker 필요)
cd frontend && npm run build    # 타입 체크 + 번들
```
