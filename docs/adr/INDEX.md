# ADR INDEX

- 001 SQL IR 직접 제작 vs Calcite — **당분간 유지 + 재검토 예약**; 스파이크 실측: 우리가 손으로 고친 표기 결함(`USING`·`NATURAL`·`WINDOW`·개명 사슬·**겹 경계**)이 관계대수에서는 **생기지 않는다**(spec 011 Q3이 공짜), 인스턴스 키는 입력 서수로 갈림, 단 `no-select-star`는 **표현 불가**(별이 펼쳐짐)이고 원문은 소실(별칭·CTE) — **정규화 이득은 관계대수에, 원문 보존은 AST에**; 재검토 시점 = **멀티 벤더**, 이주를 잴 안전망은 `ShapeCoverageTest` 55형태; 지금 가져올 것 = `NATURAL JOIN`을 거부 대신 펼치기 [IR, Calcite, 관계대수, build-vs-buy, 스파이크, 이주 안전망]
