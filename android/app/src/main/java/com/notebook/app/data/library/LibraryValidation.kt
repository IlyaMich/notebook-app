package com.notebook.app.data.library

import com.notebook.app.data.library.model.InitialPageSpec
import java.util.UUID

/** Stable repository error categories; persistence exceptions are not reported as Saved. */
enum class LibraryErrorCode {
    INVALID_INPUT,
    NOT_FOUND,
    INACTIVE_OWNER,
    CYCLE_DETECTED,
    PROTECTED_ENTITY,
    UNSUPPORTED_CONTENT,
}

class LibraryOperationException(
    val code: LibraryErrorCode,
    message: String,
) : IllegalStateException(message)

/** Input-only guards. DB ownership/cycle/content checks still belong inside transactions. */
object LibraryValidation {
    private val canonicalUuid = Regex(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
    )

    fun canonicalId(value: String): String {
        if (!canonicalUuid.matches(value)) invalid("ID must be a canonical lowercase UUID")
        try {
            if (UUID.fromString(value).toString() != value) invalid("Invalid UUID")
        } catch (error: IllegalArgumentException) {
            invalid("Invalid UUID")
        }
        return value
    }

    fun nonBlankName(value: String): String {
        val cleaned = value.trim()
        if (cleaned.isEmpty()) invalid("Folder and notebook names cannot be blank")
        return cleaned
    }

    fun initialPage(spec: InitialPageSpec): InitialPageSpec {
        if (!spec.width.isFinite() || spec.width <= 0.0 ||
            !spec.height.isFinite() || spec.height <= 0.0 ||
            spec.backgroundJson.isBlank()
        ) {
            invalid("Initial page has invalid dimensions or missing background")
        }
        return spec
    }

    private fun invalid(message: String): Nothing =
        throw LibraryOperationException(LibraryErrorCode.INVALID_INPUT, message)
}
