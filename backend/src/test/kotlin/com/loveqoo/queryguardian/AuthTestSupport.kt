package com.loveqoo.queryguardian

import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.RequestEntity
import java.net.URI

/**
 * 세션 인증 테스트 헬퍼 (spec 007 §10).
 * TestRestTemplate에는 쿠키 저장소가 없으므로 로그인 응답의 JSESSIONID를 actor별로 캐시해 Cookie 헤더로 주입한다.
 * 기존 `postAs(path, actor, body)` 시그니처를 유지해 호출 지점을 수정하지 않는다(헤더만 X-QG-Actor → Cookie).
 */
class SessionClient(private val rest: TestRestTemplate) {

    private val sessions = mutableMapOf<String, String>()

    fun cookieOf(userId: String): String = sessions.getOrPut(userId) {
        val res = rest.exchange(
            RequestEntity.post(URI("/api/auth/login")).header("Content-Type", "application/json")
                .body(mapOf("userId" to userId, "password" to "qg-demo")),
            Map::class.java)
        val setCookie = res.headers["Set-Cookie"]?.firstOrNull()
            ?: error("로그인 실패($userId): ${res.statusCode} ${res.body}")
        setCookie.substringBefore(';')
    }

    fun postAs(path: String, actor: String, body: Any? = null) = rest.exchange(
        RequestEntity.post(URI(path)).header("Cookie", cookieOf(actor))
            .header("Content-Type", "application/json").body(body ?: emptyMap<String, Any>()),
        Map::class.java)

    fun <T> postAs(path: String, actor: String, body: Any?, type: Class<T>) = rest.exchange(
        RequestEntity.post(URI(path)).header("Cookie", cookieOf(actor))
            .header("Content-Type", "application/json").body(body ?: emptyMap<String, Any>()), type)

    fun putAs(path: String, actor: String, body: Any) = rest.exchange(
        RequestEntity.put(URI(path)).header("Cookie", cookieOf(actor))
            .header("Content-Type", "application/json").body(body), Map::class.java)

    fun deleteAs(path: String, actor: String) = rest.exchange(
        RequestEntity.delete(URI(path)).header("Cookie", cookieOf(actor)).build(), Map::class.java)

    fun getAs(path: String, actor: String) = rest.exchange(
        RequestEntity.get(URI(path)).header("Cookie", cookieOf(actor)).build(), Map::class.java)

    fun getListAs(path: String, actor: String) = rest.exchange(
        RequestEntity.get(URI(path)).header("Cookie", cookieOf(actor)).build(), List::class.java)
}
