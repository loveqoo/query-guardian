package com.loveqoo.queryguardian

import com.loveqoo.queryguardian.exec.QueryExecutor
import com.loveqoo.queryguardian.query.QueryExecutionService
import com.loveqoo.queryguardian.query.gate.ApprovalCovered
import com.loveqoo.queryguardian.query.gate.Authorized
import com.loveqoo.queryguardian.query.gate.Executable
import com.loveqoo.queryguardian.query.gate.Judged
import com.loveqoo.queryguardian.query.gate.Mapped
import com.loveqoo.queryguardian.query.gate.Parsed
import com.loveqoo.queryguardian.query.gate.Planned
import com.loveqoo.queryguardian.query.gate.Ready
import com.loveqoo.queryguardian.query.gate.Storable
import com.tngtech.archunit.core.domain.JavaField
import com.tngtech.archunit.core.domain.JavaParameterizedType
import com.tngtech.archunit.core.domain.JavaType
import com.tngtech.archunit.core.domain.JavaWildcardType
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

/**
 * spec 010 **I2·I9·I10** — 게이트 밖에서 실행에 닿을 수 있는가.
 *
 * ## 왜 별도 클래스인가
 *
 * 규칙 대상이 **프로덕션 코드뿐**이다. 테스트는 실행기와 증거 타입을 알아야 한다(그래야 검증할 수 있다).
 * 한 클래스에 섞으면 규칙이 자기 검증 코드를 위반으로 잡거나, 피하려고 이름 문자열로 적게 되어
 * **이름이 어긋나면 조용히 아무것도 안 잡는 상태**가 된다 — 실제로 그렇게 만들었다가 되돌렸다.
 * `DoNotIncludeTests`로 대상을 좁히면 클래스 리터럴을 쓸 수 있고, 그러면 **컴파일러가 이름을 지킨다**.
 *
 * ## CI가 없다는 사실을 전제로 읽어라
 *
 * 이 저장소에는 `.github/workflows`가 없다 — 이 규칙들은 **누군가 테스트를 돌릴 때만** 발화한다.
 * 그래서 컴파일러가 할 수 있는 일은 컴파일러에게 맡겼다(증거 타입의 `sealed` + 전용 패키지 `query.gate`).
 * 여기 남은 것은 **컴파일러가 표현할 수 없는 성질**뿐이다.
 */
@AnalyzeClasses(
    packages = ["com.loveqoo.queryguardian"],
    importOptions = [ImportOption.DoNotIncludeTests::class],
)
class ArchGateAccessTest {

    /**
     * **I10 — 실행기는 게이트 밖에서 호출할 수 없다.**
     *
     * 백로그 D-E가 실측으로 잡은 결함이다: `QueryExecutor`는 public 빈이고 아무 제한이 없어, 새 컨트롤러나
     * 서비스가 주입하면 게이트·재작성·감사가 전부 우회된다. 우회하는 코드가 악의일 필요도 없다 —
     * "간단한 조회 하나"가 그 모양이다.
     *
     * `internal`을 붙이지 않은 이유: 모듈이 하나라 `internal`은 이 규칙이 이미 주는 것 이상을 주지 않는다.
     */
    @ArchTest
    val onlyTheGateMayReachTheExecutor: ArchRule = noClasses()
        .that().areNotAssignableTo(QueryExecutionService::class.java)
        .and().areNotAssignableTo(QueryExecutor::class.java)
        .should().dependOnClassesThat().areAssignableTo(QueryExecutor::class.java)
        .because("실행기를 주입할 수 있는 곳이 늘면 게이트를 지나지 않는 실행 경로가 생긴다 (I10)")

    /**
     * spec 010 **I9·A6**: 실행 허가증([Executable])은 **어디에도 저장되지 않는다.**
     *
     * 스펙은 단회성 토큰을 요구했는데 만들지 않았다 — 재사용이 **구조로** 성립하지 않기 때문이다.
     * 그 근거가 이것이다: 증거가 필드에 담기지 않으면 참조는 `execute`의 스택에서만 살고 호출이
     * 끝나면 사라진다. 소비 플래그는 "보관될 수 있다"를 전제할 때 필요한 장치다.
     *
     * **이 규칙이 깨지는 날이 토큰이 필요해지는 날이다** — 게이트 통과와 실행이 다른 호출로 갈라지면
     * 누군가 증거를 들고 있어야 하고, 그 순간 여기서 먼저 실패한다.
     *
     * ## 왜 `haveRawType`이 아닌가 (적대 검토가 잡은 구멍)
     *
     * 처음에는 `noFields().should().haveRawType(Executable::class.java)`였다. 그런데 **제네릭은 소거된다** —
     * `private var last: GateOutcome<Executable>?`이나 `List<Executable>`의 raw type은 `GateOutcome`·`List`이지
     * `Executable`이 아니다. 실측으로 확인했다: 그런 필드를 심어도 옛 규칙은 **통과**했다.
     * 증거를 담아 두는 가장 자연스러운 모양이 하필 그 형태(널 가능 캐시·목록)라 사각지대가 정확히
     * 위협과 겹쳤다. 그래서 타입 인자를 **재귀로** 훑는다.
     */
    @ArchTest
    val executionEvidenceIsNeverStored: ArchRule = fields()
        .should(notMentionInItsType(Executable::class.java))
        .because("실행 허가증이 필드에 담기면 그 참조를 다시 쓸 수 있다 — 단회성은 '보관되지 않음'에서 나온다")

    /**
     * spec 010 I2: 단계 증거는 **게이트 밖으로 나가지 않는다.**
     *
     * 밖에서 증거 타입을 아는 순간 그것을 받는 함수가 생길 수 있고, 그러면 증거가 게이트 밖 어딘가에
     * 담긴다. 컨트롤러·서비스가 아는 것은 게이트의 **결과**(`ExecutedQuery`·`PreviewedRewrite`)이지
     * 그 과정의 증거가 아니다.
     */
    @ArchTest
    val gateEvidenceStaysInTheGate: ArchRule = noClasses()
        .that().resideOutsideOfPackage("..queryguardian.query..")
        .should().dependOnClassesThat().belongToAnyOf(
            Parsed::class.java, Authorized::class.java, ApprovalCovered::class.java, Judged::class.java,
            Storable::class.java, Mapped::class.java, Planned::class.java,
            Ready::class.java, Executable::class.java,
        )

    // ---- 조건 --------------------------------------------------------------

    /**
     * 필드의 **선언 타입 트리 전체**에 [forbidden]이 등장하지 않는지 본다 — raw type만이 아니라
     * `GateOutcome<Executable>`·`List<Map<String, Executable>>`처럼 **감싸인 자리**까지.
     *
     * 증거를 품는 것이 일인 `…Evidence` 구현체는 제외한다. 그것들은 사슬 자체이고 요청 하나 안에서만 산다.
     */
    private fun notMentionInItsType(forbidden: Class<*>): ArchCondition<JavaField> =
        object : ArchCondition<JavaField>("타입 어디에도 ${forbidden.simpleName}를 담지 않는다") {
            override fun check(field: JavaField, events: ConditionEvents) {
                if (field.owner.simpleName.endsWith("Evidence")) return
                if (mentions(field.type, forbidden.name)) {
                    events.add(SimpleConditionEvent.violated(
                        field,
                        "${field.fullName}의 타입 ${field.type.name}이 ${forbidden.simpleName}를 담는다 — " +
                            "증거가 호출 밖으로 살아남으면 재사용·전달이 가능해진다(I9)",
                    ))
                }
            }
        }

    /**
     * **와일드카드까지 내려가야 한다.** `GateOutcome<out T>`는 공변이라 필드 시그니처에서
     * `GateOutcome<? extends Executable>`가 된다 — 타입 인자가 클래스가 아니라 와일드카드다.
     * 처음엔 [JavaParameterizedType]만 훑었고, 그래서 `List<Executable>`은 잡히는데
     * `GateOutcome<Executable>?`은 **통과했다**(실측). 증거를 담는 가장 자연스러운 모양이 하필 후자다.
     */
    private fun mentions(type: JavaType, fqn: String): Boolean = when {
        type.name == fqn -> true
        type is JavaParameterizedType -> type.actualTypeArguments.any { mentions(it, fqn) }
        type is JavaWildcardType ->
            (type.upperBounds + type.lowerBounds).any { mentions(it, fqn) }
        else -> false
    }
}
