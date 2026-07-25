package com.loveqoo.queryguardian.approval

import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.data.repository.query.Param

interface ApprovalRequestRepository : CrudRepository<ApprovalRequest, Long> {
    @Query("SELECT * FROM approval_request WHERE status = :status AND requester = :requester")
    fun findByStatusAndRequester(@Param("status") status: String, @Param("requester") requester: String): List<ApprovalRequest>
}

interface ApprovalEventRepository : CrudRepository<ApprovalEvent, Long>

interface QueryReviewEventRepository : CrudRepository<QueryReviewEvent, Long>

/**
 * 관리형 디렉터리 (spec 005 §7) — 사용자·승인자·비즈니스 요건 화이트리스트.
 * actor는 **ASCII id**(HTTP 헤더 제약 + 이름 변경에 강함)이고 이름·역할은 디렉터리가 해석한다.
 * 인증 도입 전 스텁 identity의 근거이며 **접근 통제가 아니다**(§5).
 */
object Directory {
    data class Person(val id: String, val name: String, val role: String)
    data class BusinessReq(val code: String, val label: String, val desc: String)

    val users = listOf(
        Person("u1", "김도현", "데이터 분석가"), Person("u2", "이서연", "데이터 분석가"),
        Person("u3", "박민준", "데이터 엔지니어"), Person("u4", "정하윤", "데이터 거버넌스"),
    )
    val approvers = listOf(
        Person("ap1", "최지훈", "마케팅본부장"), Person("ap2", "한도윤", "데이터플랫폼장"),
        Person("ap3", "서준호", "정보보호책임자(CISO)"), Person("ap4", "김영은", "최고데이터책임자(CDO)"),
    )
    val businessReqs = listOf(
        BusinessReq("marketing", "마케팅 수신 동의자 한정", "is_agreed = TRUE 인 사용자만 조회"),
        BusinessReq("pii", "개인정보(PII) 포함", "이메일·전화번호 등 식별정보 조회 필요"),
        BusinessReq("mask", "민감정보 마스킹 적용", "주민번호·카드번호는 마스킹 처리"),
        BusinessReq("retention", "보관기간 준수", "수집 후 최대 90일 데이터만 사용"),
        BusinessReq("external", "외부 반출 목적", "제3자 제공/반출 검토 필요"),
    )

    fun findUser(id: String) = users.firstOrNull { it.id == id }
    fun findApprover(id: String) = approvers.firstOrNull { it.id == id }
    fun findAnyone(id: String) = findUser(id) ?: findApprover(id)
    fun hasBusinessReq(code: String) = businessReqs.any { it.code == code }
}
