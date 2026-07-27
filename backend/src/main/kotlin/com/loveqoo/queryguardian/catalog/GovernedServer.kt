package com.loveqoo.queryguardian.catalog

/**
 * **이 제품이 통제하는 서버**. 오늘은 하나다.
 *
 * ## 왜 상수가 여기 있는가
 *
 * 이 키는 이미 시스템 여러 곳에서 **사실**로 쓰이고 있었다 — `user_server_permission.server_key`가
 * 이 값으로 저장되고, 접근 판정과 권한 편집이 이 값으로 조회한다. 그런데 정의가 **두 벌**이었다
 * (`AccessControl`·`UserAdminService`가 각자 `private val DEFAULT_SERVER = "mysql-prod"`).
 * 사본이 둘이면 한쪽만 고쳐지는 날이 온다 — 그날 권한 편집은 A 서버를 끄고 접근 판정은 B 서버를 본다.
 *
 * ## 그리고 화면이 거짓말하고 있었다
 *
 * 규칙 편집 화면은 **세 개의 서버**(MySQL·PostgreSQL·Trino)를 고르게 하고 호스트·클러스터 구성·
 * 노드 수까지 보여 줬다. 전부 디자인 샘플이다. 담당자가 `pg-analytics`를 골라 규칙을 만들면
 * 그 규칙의 대상 서버는 **어디에도 없는 값**이 되고, 게다가 규칙 평가는 이 필드를 보지도 않는다
 * (`UserRuleEvaluator`는 대상 테이블 참조만 본다). 고른 값이 아무 일도 안 하는데
 * 화면은 골라야 하는 것처럼 굴었다.
 *
 * 그래서 **목록을 서버가 준다.** 화면이 아는 서버 = 실제로 있는 서버가 된다.
 *
 * ## 늘어날 때
 *
 * 멀티 벤더(spec 014 X2)에서 이것은 테이블이 된다. 그때 바꿀 것은 이 파일 하나이고,
 * **규칙의 `server` 필드를 평가가 실제로 보게 만드는 것**이 함께 와야 한다 —
 * 지금처럼 무시하면 A 서버 규칙이 B 서버 쿼리를 판정한다.
 */
object GovernedServer {
    /** `user_server_permission.server_key`에 저장되는 값. 바꾸면 기존 권한 행이 고아가 된다. */
    const val KEY: String = "mysql-prod"

    /** 사람에게 보이는 이름. 표시 전용이므로 자유롭게 바꿔도 저장된 데이터에 영향이 없다. */
    const val LABEL: String = "MySQL (기본)"

    val ALL: List<ServerDescriptor> = listOf(ServerDescriptor(KEY, LABEL))
}

/**
 * 화면에 주는 서버 정보. **아는 것만 담는다.**
 *
 * 호스트·클러스터 구성·노드 수는 일부러 없다 — 이 앱은 그것을 모른다.
 * 모르는 것을 화면에 그리면 그 순간 화면이 거짓말을 시작한다(그것이 방금 고친 결함이다).
 */
data class ServerDescriptor(val key: String, val label: String)
