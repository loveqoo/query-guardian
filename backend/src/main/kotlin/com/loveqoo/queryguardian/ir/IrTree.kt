package com.loveqoo.queryguardian.ir

/**
 * IR을 아스키 트리로 그린다 — `gradle dependencies`처럼 **구조를 눈으로 보기 위한** 도구.
 *
 * IR은 이 제품에서 룰이 바라보는 **유일한 어휘**인데(spec 001 §5.2) 정작 사람이 볼 방법이 없었다.
 * 데이터 클래스 기본 `toString()`은 한 줄로 쏟아져 스코프 중첩이 안 보이고, 판정 사각은 대부분
 * "어느 스코프가 등록되지 않았는가"에서 생긴다(ScopeCoverageTest가 그 이유로 존재한다).
 * 구조가 보이면 그 사각도 보인다.
 *
 * **아무 패키지도 의존하지 않는다** — `ir`은 공용 어휘이고 ArchUnit이 그 고립을 강제한다.
 *
 * 표기: `!` 접두는 **fail-closed로 떨어지는 사실**이다(표현 불가·귀속 불가·주입 불가).
 */
fun QueryIR.toAsciiTree(): String {
    val root = Node("QueryIR  (raw ${raw.length}자)")
    root.scope(this.root)
    return root.render()
}

// ---- 트리 모델 -------------------------------------------------------------

/**
 * "무엇을 보여줄까"와 "어떻게 그릴까"를 나눈다. 렌더링은 [render] 한 곳에만 있고, 아래 `scope`·
 * `predicate` 함수들은 **라벨만** 만든다 — 접두사 계산이 각 함수로 번지면 손댈 때마다 어긋난다.
 */
private class Node(val label: String) {
    val children = mutableListOf<Node>()

    fun add(label: String): Node = Node(label).also { children += it }

    fun addAll(label: String, items: List<String>) {
        if (items.isEmpty()) return
        val group = add(label)
        items.forEach { group.add(it) }
    }

    fun render(): String = buildString {
        appendLine(label)
        renderChildren(this@Node, "", this)
    }

    private fun renderChildren(node: Node, prefix: String, out: StringBuilder) {
        node.children.forEachIndexed { i, child ->
            val last = i == node.children.lastIndex
            out.appendLine(prefix + (if (last) "└─ " else "├─ ") + child.label)
            renderChildren(child, prefix + (if (last) "   " else "│  "), out)
        }
    }
}

/**
 * 원문 조각을 한 줄로 접는다. Druid가 되돌려주는 서브쿼리 텍스트에는 줄바꿈·탭이 들어 있어
 * 그대로 넣으면 트리의 가지 문자가 어긋난다 — **읽으려고 만든 도구가 못 읽게 된다.**
 */
private fun oneLine(text: String, max: Int = 100): String {
    val flat = text.replace(Regex("\\s+"), " ").trim()
    return if (flat.length <= max) flat else flat.take(max - 1) + "…"
}

// ---- 스코프 ----------------------------------------------------------------

private fun Node.scope(scope: SelectScope) {
    val id = scope.scopeId.ifEmpty { "?" }
    val node = add("${scope.kind} [$id]${scopeFlags(scope)}")

    // 표현 불가는 무조건 차단이므로 맨 위에 세운다 — 다른 무엇보다 먼저 보여야 한다.
    scope.unverifiable?.let { node.add("!unverifiable: ${oneLine(it)}") }

    node.addAll("tables", scope.tables.map(::tableLabel))
    node.addAll("select", scope.selectItems.map(::selectLabel))

    if (scope.whereConjuncts.isNotEmpty()) {
        val where = node.add("where (top-level AND conjuncts)")
        scope.whereConjuncts.forEach { where.predicate(it) }
    }

    scope.limit?.let { node.add("limit $it") }
    node.addAll("columnRefs", scope.columnRefs.map(::columnRefLabel))
    node.addAll("joinEqualities", scope.joinEqualities.map {
        "${columnRefLabel(it.left)} = ${columnRefLabel(it.right)}"
    })
    if (scope.outputRefs.isNotEmpty()) node.add("outputRefs: ${scope.outputRefs.sorted().joinToString(", ")}")

    if (scope.children.isNotEmpty()) {
        val kids = node.add("children (${scope.children.size})")
        scope.children.forEach { kids.scope(it) }
    }
}

/** 기본값에서 벗어난 사실만 적는다 — 전부 적으면 눈에 안 들어온다. */
private fun scopeFlags(scope: SelectScope): String {
    val flags = buildList {
        if (scope.distinct) add("DISTINCT")
        if (!scope.injectable) add("!injectable")
        if (scope.nullProducingInstances.isNotEmpty()) {
            add("nullProducing=${scope.nullProducingInstances.sorted().joinToString("|")}")
        }
    }
    return if (flags.isEmpty()) "" else "  ${flags.joinToString(" ")}"
}

private fun tableLabel(t: TableRef): String {
    val alias = t.alias?.let { " AS $it" } ?: ""
    val kind = if (t.physical) "physical" else "derived"
    return "${t.name}$alias  ($kind, key=${t.instanceKey})"
}

private fun selectLabel(item: SelectItem): String = when (item) {
    is SelectItem.Column -> {
        val owner = item.column.table ?: "!unresolved"
        "$owner.${item.column.column}" + (item.alias?.let { " AS $it" } ?: "")
    }
    is SelectItem.Star -> (item.qualifier?.let { "$it.*" } ?: "*") + "  (star)"
    is SelectItem.Expr -> "expr: ${oneLine(item.text)}"
}

/** 귀속 실패는 `!`로 드러낸다 — 룰이 fail-closed로 처리하는 지점이라 눈에 띄어야 한다. */
private fun columnRefLabel(ref: ColumnRef): String =
    "${ref.table?.instanceKey ?: "!unattributed"}.${ref.column}"

private fun resolvedLabel(c: ResolvedColumn): String = "${c.table ?: "!unattributed"}.${c.column}"

// ---- 술어 ------------------------------------------------------------------

private fun Node.predicate(p: Predicate) {
    when (p) {
        is Predicate.Comparison ->
            add("${resolvedLabel(p.column)} ${p.op} ${p.value?.let { "'$it'" } ?: "!non-literal"}")
        is Predicate.InList ->
            add("${resolvedLabel(p.column)} IN ${p.values?.joinToString(", ", "(", ")") ?: "!non-literal"}")
        is Predicate.Between ->
            add("${resolvedLabel(p.column)} BETWEEN ${p.low ?: "!"} AND ${p.high ?: "!"}")
        is Predicate.And -> add("AND").also { n -> p.conjuncts.forEach { n.predicate(it) } }
        is Predicate.Or -> add("OR").also { n -> p.branches.forEach { n.predicate(it) } }
        is Predicate.Not -> add("NOT").also { it.predicate(p.inner) }
        // Raw는 어떤 요건도 충족시키지 못한다(§6.3) — 그 사실이 보여야 한다.
        is Predicate.Raw -> add("!raw: ${oneLine(p.fragment)}")
    }
}
