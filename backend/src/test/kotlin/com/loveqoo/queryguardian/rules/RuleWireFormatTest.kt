package com.loveqoo.queryguardian.rules

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * spec 010 P3 · R1 — **규칙 트리의 와이어 형태는 계약이다.**
 *
 * ## 이 테스트가 enum보다 먼저 쓰인 이유
 *
 * `RuleOp`·`Combinator`는 상수 이름이 소문자였다(`requires`, `all`). 코틀린 관례를 어긴 것이지만
 * **고칠 때 깨지는 것이 컴파일러가 잡아 주는 범위 밖에 있다**:
 *
 * | 어디 | 무엇 |
 * |---|---|
 * | DB | `rule.tree_json`에 `{"op":"requires"}`가 **이미 저장되어 있다** |
 * | 프론트 | 소문자 문자열을 **타입 유니온과 테마 맵의 키**로 쓴다(`theme.ts`, `mock/design.ts`) |
 *
 * 그래서 R1의 답은 "이름을 대문자로 바꾸기"가 아니라 **"상수 이름과 와이어 표현을 분리하기"** 다.
 * 이 파일은 그 분리가 **표현을 한 글자도 바꾸지 않았다**를 지킨다 — 상수 이름을 고치기 **전에**
 * 작성해 통과를 확인했고, 그래서 "새 코드에 맞춰 쓴 테스트"가 아니다.
 *
 * ## 되돌려 실패 (A8)
 *
 * `RuleOp`에서 `@JsonValue`를 떼면 직렬화가 `"REQUIRES"`가 되어 아래 셋이 전부 깨진다.
 */
class RuleWireFormatTest {

    private val mapper = ObjectMapper().registerKotlinModule()

    /** 저장·전송되는 형태 그대로. 이 문자열이 곧 계약이다. */
    private val storedTree = """
        {"node":"group","combinator":"all","children":[
          {"node":"cond","op":"requires","severity":"BLOCK","table":"users","column":"email","defId":1},
          {"node":"cond","op":"must_be_masked","severity":"WARN","table":"users","column":"ssn","defId":2},
          {"node":"group","combinator":"any","children":[
            {"node":"cond","op":"joins","severity":"BLOCK","table":"a","column":"b","refTable":"c","refColumn":"d"},
            {"node":"cond","op":"blocks","severity":"BLOCK","table":"t","column":"c","defId":3},
            {"node":"cond","op":"must_be_within","severity":"WARN","table":"t","column":"d","defId":4}
          ]}
        ]}
    """.trimIndent()

    @Test
    fun `저장된 트리가 그대로 역직렬화된다`() {
        val tree = mapper.readValue(storedTree, RuleNode::class.java)
        assertTrue(tree is RuleGroup, "그룹으로 읽히지 않았다: $tree")

        val ops = collectConditions(tree).map { it.op }
        assertEquals(
            listOf(RuleOp.REQUIRES, RuleOp.MUST_BE_MASKED, RuleOp.JOINS, RuleOp.BLOCKS, RuleOp.MUST_BE_WITHIN),
            ops,
            "저장된 소문자 표현이 상수로 읽히지 않았다 — 기존 규칙이 손상(corrupt)으로 떨어진다",
        )
        assertEquals(RuleGroup.Combinator.ALL, tree.combinator)
        assertEquals(
            RuleGroup.Combinator.ANY,
            tree.children.filterIsInstance<RuleGroup>().single().combinator,
        )
    }

    /**
     * **직렬화가 소문자를 그대로 낸다.** 프론트가 이 값을 테마 맵의 키로 쓰므로, 대문자로 바뀌면
     * 화면에서 배지 색과 라벨이 조용히 사라진다 — 500도 아니고 로그도 없다.
     */
    @Test
    fun `직렬화가 와이어 표현을 유지한다`() {
        val json = mapper.writeValueAsString(mapper.readValue(storedTree, RuleNode::class.java))
        for (wire in listOf("requires", "must_be_masked", "joins", "blocks", "must_be_within")) {
            assertTrue("\"op\":\"$wire\"" in json, "op 표현이 바뀌었다($wire): $json")
        }
        for (wire in listOf("all", "any")) {
            assertTrue("\"combinator\":\"$wire\"" in json, "combinator 표현이 바뀌었다($wire): $json")
        }
        // 상수 이름이 새어 나가지 않는다
        assertTrue("REQUIRES" !in json && "ALL" !in json, "상수 이름이 와이어로 나갔다: $json")
    }

    /** 왕복 후 값이 같아야 한다 — 표현이 유지되는 것만으로는 의미 보존을 증명하지 못한다. */
    @Test
    fun `왕복해도 트리가 같다`() {
        val once = mapper.readValue(storedTree, RuleNode::class.java)
        val twice = mapper.readValue(mapper.writeValueAsString(once), RuleNode::class.java)
        assertEquals(once, twice, "왕복에서 트리가 달라졌다")
    }

    /**
     * **모든 값이 검사되는가** — 값 하나를 추가하고 이 테스트를 잊으면 그 값만 와이어 계약 밖에 남는다.
     * 그러면 새 연산자를 쓴 규칙이 저장은 되고 읽기에서 손상으로 떨어진다.
     */
    @Test
    fun `모든 enum 값이 와이어 표현을 갖는다`() {
        assertEquals(
            RuleOp.entries.size,
            RuleOp.entries.map { mapper.writeValueAsString(it) }.distinct().size,
            "와이어 표현이 겹치는 값이 있다",
        )
        for (op in RuleOp.entries) {
            val wire = mapper.writeValueAsString(op)
            assertTrue(
                wire == wire.lowercase(),
                "${op.name}의 와이어 표현이 소문자가 아니다: $wire — 프론트가 키로 쓰는 규약을 깬다",
            )
            assertEquals(op, mapper.readValue(wire, RuleOp::class.java), "${op.name}이 왕복하지 않는다")
        }
        for (c in RuleGroup.Combinator.entries) {
            val wire = mapper.writeValueAsString(c)
            assertTrue(wire == wire.lowercase(), "${c.name}의 와이어 표현이 소문자가 아니다: $wire")
            assertEquals(c, mapper.readValue(wire, RuleGroup.Combinator::class.java), "${c.name}이 왕복하지 않는다")
        }
    }

    private fun collectConditions(node: RuleNode): List<RuleCondition> = when (node) {
        is RuleCondition -> listOf(node)
        is RuleGroup -> node.children.flatMap { collectConditions(it) }
    }
}
