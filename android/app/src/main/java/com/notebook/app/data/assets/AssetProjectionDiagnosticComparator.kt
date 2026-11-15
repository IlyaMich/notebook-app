package com.notebook.app.data.assets

/**
 * STO-08 I5-A4: compare supplied canonical owner observations with supplied
 * projection observations. Diagnostic only: neither input is proven complete,
 * and an empty finding list is NOT an index VERIFIED state or GC authority.
 * No Room access, file access, side effects, or cleanup decisions.
 */
internal object AssetProjectionDiagnosticComparator {
    data class PageEdge(val pageId: String, val assetId: String)
    data class ElementEdge(val elementId: String, val assetId: String)

    enum class Kind {
        UNRESOLVED_CANONICAL_CONTENT,
        INVALID_CANONICAL_OWNER,
        DUPLICATE_CANONICAL_OWNER,
        DUPLICATE_PROJECTION_EDGE,
        MISSING_PROJECTION_EDGE,
        MISMATCHED_ASSET_ID,
        UNEXPECTED_PROJECTION_EDGE,
    }

    data class Finding(val kind: Kind, val ownerType: String, val ownerId: String)
    data class Diagnostic(val findings: List<Finding>) {
        /** Zero findings describes ONLY these supplied observations. */
        val hasObservedMismatch: Boolean get() = findings.isNotEmpty()
    }

    fun compareObserved(
        canonical: AssetOwnerInventory.Report,
        pageEdges: List<PageEdge>,
        elementEdges: List<ElementEdge>,
    ): Diagnostic {
        val findings = mutableListOf<Finding>()
        canonical.issues.forEach {
            findings += Finding(Kind.UNRESOLVED_CANONICAL_CONTENT, "canonical", it.elementId ?: it.pageId)
        }
        val pages = mutableMapOf<String, String>()
        val elements = mutableMapOf<String, String>()
        for (owner in canonical.owners) {
            val isPage = owner.kind == AssetOwnerInventory.Kind.PDF_BACKGROUND
            val ownerId = if (isPage) owner.pageId else owner.elementId
            val type = if (isPage) "page" else "element"
            if (owner.assetId.isBlank() || ownerId.isNullOrBlank() ||
                (isPage && owner.elementId != null)
            ) {
                findings += Finding(Kind.INVALID_CANONICAL_OWNER, type, ownerId ?: "")
                continue
            }
            val target = if (isPage) pages else elements
            if (target.putIfAbsent(ownerId, owner.assetId) != null) {
                findings += Finding(Kind.DUPLICATE_CANONICAL_OWNER, type, ownerId)
            }
        }
        checkEdges("page", pages, pageEdges.map { it.pageId to it.assetId }, findings)
        checkEdges("element", elements, elementEdges.map { it.elementId to it.assetId }, findings)
        return Diagnostic(findings.sortedWith(compareBy({ it.ownerType }, { it.ownerId }, { it.kind.name })))
    }

    private fun checkEdges(
        type: String,
        expected: Map<String, String>,
        supplied: List<Pair<String, String>>,
        findings: MutableList<Finding>,
    ) {
        val observed = mutableMapOf<String, String>()
        for ((id, assetId) in supplied) {
            if (id.isBlank() || assetId.isBlank()) {
                findings += Finding(Kind.INVALID_CANONICAL_OWNER, type, id)
                continue
            }
            if (observed.putIfAbsent(id, assetId) != null) {
                findings += Finding(Kind.DUPLICATE_PROJECTION_EDGE, type, id)
            }
        }
        for ((id, asset) in expected) {
            val actual = observed[id]
            when {
                actual == null -> findings += Finding(Kind.MISSING_PROJECTION_EDGE, type, id)
                actual != asset -> findings += Finding(Kind.MISMATCHED_ASSET_ID, type, id)
            }
        }
        for (id in observed.keys) {
            if (id !in expected) findings += Finding(Kind.UNEXPECTED_PROJECTION_EDGE, type, id)
        }
    }
}