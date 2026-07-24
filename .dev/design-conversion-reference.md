# 디자인 컨버팅 기준 (spec 003 실행 자산)

> 원본: `docs/design/query-guardian-design/Query Guardian.dc.html` (2027줄).
> 디자인은 이미 antd 컴포넌트(`x-import ... AntDesignSystem_b19dda.*`)로 구성됨 — 사실상 antd 앱.
> 데이터·로직은 라인 836~2027의 `<script type="text/x-dc">` 안에 완결돼 있다(state + 헬퍼 메서드).
> 컨버팅 = 이 x-dc 데이터를 `src/mock/design.ts`로 옮기고, 각 화면 템플릿을 antd JSX로 옮기는 것.

## 화면 템플릿 원본 좌표 (dc.html 라인)

| 화면 | 라인 | 라우트 | 등급 |
|---|---|---|---|
| SIDER (셸 좌측) | 31–56 | (공통) | — |
| MAIN 헤더 (브레드크럼) | 57–77 | (공통) | — |
| 데이터베이스 | 78–238 | /databases | B |
| 쿼리 에디터 | 239–368 | /editor | A |
| 저장된 쿼리 | 369–416 | /queries | A |
| 승인 요청 | 417–556 | /approvals | C |
| 규칙 관리 | 558–640 | /rules | C |
| 접근 권한 관리 | 641–688 | /admin | C |
| 제약 카탈로그 | 690–754 | /catalog | A |
| DEF 모달 (정의 편집) | 755–777 | (카탈로그) | A |
| MAP 모달 (컬럼 매핑) | 778–801 | (카탈로그) | A |
| DETAIL 모달 (쿼리 상세) | 802–835 | (저장된 쿼리) | A |
| x-dc 데이터·로직 | 836–2027 | (전역) | — |

## 디자인 토큰 (antd ConfigProvider theme)

- **사이더 배경**: `#001529` (다크). 로고 그라디언트 `linear-gradient(135deg,#1677ff,#0958d9)`.
- **primary**: `#1677ff` (colorPrimary). 링크 hover `#0958d9`.
- 헤더/콘텐츠 배경 `#fff`, 레이아웃 배경 `--color-bg-layout`(antd 기본 `#f5f5f5`).
- 카드 `border:1px solid #f0f0f0(border-secondary); border-radius:8px`.
- 폰트: 본문 antd 기본, **코드/식별자는 `ui-monospace,SFMono-Regular,Consolas,Menlo,monospace`**.
- 진입 애니메이션 `qgFade .2s`(opacity+translateY 6px).

### 추가 세부 (적대 검토 확인분)

- **컬럼 키 태그**: PK=`gold`·FK=`geekblue`·UK=`cyan`·PARTITION=`purple`·IDX=`blue`·CHECK=`green`.
- **인덱스 타입 텍스트색**: PRIMARY=gold-7·UNIQUE=cyan-7·INDEX=blue-7·FOREIGN=geekblue-7·PARTITION=purple-7.
- **다크 코드 패널**(AI SQL·IR 트리·정의 미리보기): 배경 `#0b1220`, 텍스트 `#e6edf3`.
- **JSON 트리**(규칙 트리): key`#c9d1d9`·str`#a5d6a4`·num`#f0a878`·bool`#79b8ff`·type`#8b98a5`.
- **추천 팝업 배지**: F(table)`#e6f4ff/#1677ff`·C(column)`#f9f0ff/#722ed1`·ƒ(function)`#e6fffb/#08979c`·K(keyword)`#f0f5ff/#2f54eb`.
- **macOS 신호등**: `#ff5f56/#ffbd2e/#27c93f`. **레이아웃 배경** `#f5f5f5`, 사이더 너비 **236px**.
- **에디터 벤더 버전 라벨**: MySQL "MySQL 8.0" · PostgreSQL "PostgreSQL 16" · Trino "Trino 440".
- **breadcrumb = 그룹명 단독**(chevron·화면명 없음). 상태 draft/cancelled = `default`(무색).

### 태그 색 (antd Tag color 값 — 그대로 사용)

- **벤더**: MySQL=`blue`, PostgreSQL=`geekblue`, Trino=`purple`.
- **제약 kind**: mask=`purple`(마스킹), filter=`blue`(필터), block=`red`(차단), join=`cyan`(조인), integrity=`geekblue`(무결성). (PARTITION은 백엔드 spec 002 추가분 — 디자인 미존재, 컨버팅 시 `orange`/파티션 유지)
- **규칙 연산자(op)**: requires=`blue`(요건 필요), joins=`cyan`(조인 강제), must_be_within=`gold`(기간 이내), must_be_masked=`purple`(마스킹 필수), blocks=`red`(차단).
- **severity**: error=빨강 "차단 (오류)", warning=골드 "경고".
- **승인 상태**: pending=승인 대기, approved=승인됨, rejected=반려됨, cancelled=요청 취소됨.
- **SQL 하이라이터 색**(에디터·상세): 주석 `#8c8c8c`, 문자열 `#c41d7f`, 숫자 `#d46b08`, 키워드 `#0958d9`(bold), 함수 `#08979c`.

## SQL 하이라이팅

디자인은 자체 `highlight(sql)` 정규식 하이라이터를 씀(라인 1179~1198). 실제 에디터는 CodeMirror 유지 —
읽기 전용 프리뷰(쿼리 상세 모달·에디터 코드 창 표시)는 CodeMirror 또는 동일 색 규칙의 `<pre>` 사용.

## 샘플 데이터 (x-dc state → src/mock/design.ts로 이관)

핵심 목록(전문은 dc.html 라인 참조):
- `databases` (940–944): prod-main(MySQL·42테이블)/analytics-dw(PG·88)/data-lake(Trino·213), host 포함.
- `tablesByDb` (946–959), `columnsByTable` (961–993, PK/UK/FK/IDX/PARTITION·isPii·nullable·def·comment), `indexesByTable` (1059–1084), `tableMetaByName`(엔진/인코딩/로우) (1008–1014).
- `constraintDefs` (882–904): 클래스 6종별 정의 21개(kind·expr 포함) — **카탈로그 실 데이터의 시드 후보이기도**.
- `colConstraints` (905–913): 컬럼→정의 매핑.
- `rulesMeta` (1157–1161) + `ruleTrees` (915–930): 규칙 3종(r1 PII 마스킹·r2 마케팅 동의·r3 LIMIT), AND 트리 조건.
- `baseApprovals` (1090–1094): REQ-1043(pending)/1041(approved)/1038(rejected).
- `businessReqs` (1096–1102) 5종, `approverPool` (1112–1117) 4역할(마케팅본부장/데이터플랫폼장/CISO/CDO), `reqTableOptions`, `reqApprovers`.
- `users` (1119–1124) 4명 + `buildDefaultPerms` (1125–1140) DB/테이블 권한 + u1은 trino·payments·sessions 차단.
- `servers` (1163–1167): 클러스터 구성·노드 수.
- `kindMeta`(action·skeleton) (1017–1023), `opMeta` (1169–1175), `classLabels` (1024).
- 헬퍼: `colClass` (1031–1042 판별), `enforcePreview` (1025–1029), `highlight`, `mockSql` (1199 AI 스텁 SQL), `condValueLabel`.

## A등급 화면의 실 API 연결 (스텁 아님)

- 에디터: `POST /api/lint`(debounce)·`POST/PUT /api/queries`·`GET /api/catalog/schema`(자동완성). 벤더 select는 MySQL만 활성.
- 저장된 쿼리: `GET/PUT/DELETE /api/queries`. 승인 상태 컬럼은 스텁 값.
- 카탈로그: spec 002 API(`/api/catalog/defs|mappings|tables|purposes`) — 기존 CatalogPage 로직 유지하되 디자인 톤·섹션으로 재배치.

## antd 컴포넌트 매핑 (x-import → antd)

Menu(theme=dark,mode=inline) · Layout(Sider/Header/Content) · Tag · Button · Input · Select · Table ·
Tabs · Modal · Switch · Checkbox · Radio(기본/어드밴스드 토글) · Tooltip · Popconfirm · Alert · message · Empty ·
Segmented(탭 토글 후보) · Tree(규칙 IR 트리). 아이콘은 `@ant-design/icons`(lock/appstore/code/file/check-circle/thunderbolt/bulb/key/bell/question-circle/logout/search).
