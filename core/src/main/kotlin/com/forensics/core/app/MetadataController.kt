package com.forensics.core.app

import com.forensics.core.InspectionResult
import com.forensics.core.Inspector
import com.forensics.core.engine.EditEngine
import com.forensics.core.engine.Guard
import com.forensics.core.handler.FormatHandler
import com.forensics.core.io.ByteSink
import com.forensics.core.io.ByteSource
import com.forensics.core.model.EditResult
import com.forensics.core.model.MetadataField
import com.forensics.core.model.Value

/**
 * Pure-Kotlin orchestration over the core: inspect a source and apply a corruption-safe edit.
 * Holds no Android dependency, so it is fully JVM-unit-testable. The Android layer supplies the
 * [ByteSource]/[ByteSink] (PFD-backed) and calls these two methods.
 */
class MetadataController(private val handlers: List<FormatHandler>) {
    private val inspector = Inspector(handlers)

    fun inspect(source: ByteSource): InspectionResult = inspector.inspect(source)

    /**
     * Applies an edit of [field] to [newValue], selecting the handler by magic-byte detection,
     * compiling a plan, and executing it through the safety engine. Returns Failure if no handler
     * matches or the plan is rejected — never throws on ordinary failure.
     */
    fun edit(sink: ByteSink, source: ByteSource, field: MetadataField, newValue: Value): EditResult {
        val handler = matchHandler(source)
            ?: return EditResult.Failure("no handler recognizes this file")
        val plan = runCatching { handler.validateEdit(source, field, newValue) }
            .getOrElse { return EditResult.Failure("could not compile edit: ${it.message}") }
        val guard = Guard.capture(source)
        return EditEngine(handler).apply(sink, plan, guard, field, newValue)
    }

    private fun matchHandler(source: ByteSource): FormatHandler? {
        val n = minOf(16L, source.size()).toInt()
        val magic = if (n > 0) source.readAt(0, n) else ByteArray(0)
        return handlers.firstOrNull { it.canHandle(magic, null) }
    }
}
