package com.forensics.core.engine

import com.forensics.core.handler.FormatHandler
import com.forensics.core.io.ByteSink
import com.forensics.core.io.InMemoryByteSource
import com.forensics.core.model.EditPlan
import com.forensics.core.model.EditResult
import com.forensics.core.model.MetadataField
import com.forensics.core.model.Value

/**
 * Executes [EditPlan]s with defense-in-depth:
 *  - fail closed on Rejected
 *  - concurrent-modification guard before any write
 *  - strict same-length in-place invariant
 *  - undo buffer + read-back verification + structural re-parse, restore on failure
 */
class EditEngine(private val handler: FormatHandler) {

    fun apply(
        sink: ByteSink,
        plan: EditPlan,
        guard: GuardToken,
        field: MetadataField,
        expectedNewValue: Value,
    ): EditResult = when (plan) {
        is EditPlan.Rejected -> EditResult.Failure(plan.reason)
        is EditPlan.InPlace -> applyInPlace(sink, plan, guard, field, expectedNewValue)
        is EditPlan.RequiresRewrite -> applyRewrite(sink, plan, guard, field, expectedNewValue)
    }

    private fun currentSource(sink: ByteSink) =
        InMemoryByteSource(sink.readAt(0, sink.size().toInt()))

    private fun applyInPlace(
        sink: ByteSink, plan: EditPlan.InPlace, guard: GuardToken,
        field: MetadataField, expected: Value,
    ): EditResult {
        if (!Guard.matches(guard, currentSource(sink)))
            return EditResult.Failure("file changed since it was read")

        if (plan.newBytes.size != plan.originalBytes.size)
            return EditResult.Failure("in-place plan is not length-preserving")
        if (plan.offset < 0 || plan.offset + plan.newBytes.size > sink.size())
            return EditResult.Failure("in-place write out of bounds")

        val undoValue = sink.readAt(plan.offset, plan.newBytes.size)
        val undoChecksums = plan.checksumPatches.map { it.offset to sink.readAt(it.offset, it.bytes.size) }

        try {
            sink.writeAt(plan.offset, plan.newBytes)
            plan.checksumPatches.forEach { sink.writeAt(it.offset, it.bytes) }
            sink.force()

            if (!sink.readAt(plan.offset, plan.newBytes.size).contentEquals(plan.newBytes))
                return restore(sink, plan.offset, undoValue, undoChecksums, "read-back mismatch")
            plan.checksumPatches.forEach {
                if (!sink.readAt(it.offset, it.bytes.size).contentEquals(it.bytes))
                    return restore(sink, plan.offset, undoValue, undoChecksums, "checksum read-back mismatch")
            }

            val reparsed = handler.parse(currentSource(sink))
            val match = reparsed.firstOrNull { it.key == field.key && it.byteOffset == field.byteOffset }
            if (match == null || match.value != expected)
                return restore(sink, plan.offset, undoValue, undoChecksums, "structural re-parse failed")

            return EditResult.Success(inPlace = true, bytesPatched = plan.newBytes.size)
        } catch (t: Throwable) {
            return restore(sink, plan.offset, undoValue, undoChecksums, "write error: ${t.message}")
        }
    }

    private fun restore(
        sink: ByteSink, valueOffset: Long, undoValue: ByteArray,
        undoChecksums: List<Pair<Long, ByteArray>>, reason: String,
    ): EditResult {
        runCatching {
            sink.writeAt(valueOffset, undoValue)
            undoChecksums.forEach { (off, bytes) -> sink.writeAt(off, bytes) }
            sink.force()
        }
        return EditResult.Failure(reason)
    }

    private fun applyRewrite(
        sink: ByteSink, plan: EditPlan.RequiresRewrite, guard: GuardToken,
        field: MetadataField, expected: Value,
    ): EditResult {
        if (!Guard.matches(guard, currentSource(sink)))
            return EditResult.Failure("file changed since it was read")

        val staged = InMemoryByteSource(plan.rebuiltBytes)
        val parsed = runCatching { handler.parse(staged) }.getOrNull()
            ?: return EditResult.Failure("rebuilt file does not parse")
        val match = parsed.firstOrNull { it.key == field.key }
        if (match == null || match.value != expected)
            return EditResult.Failure("rebuilt file does not contain the expected value")

        val undoWhole = sink.readAt(0, sink.size().toInt())
        try {
            sink.rewrite(plan.rebuiltBytes.inputStream())
            sink.force()
            val reparsed = handler.parse(currentSource(sink))
            val ok = reparsed.firstOrNull { it.key == field.key }?.value == expected
            if (!ok) {
                sink.rewrite(undoWhole.inputStream()); sink.force()
                return EditResult.Failure("post-rewrite verification failed")
            }
            return EditResult.Success(inPlace = false, bytesPatched = plan.rebuiltBytes.size)
        } catch (t: Throwable) {
            runCatching { sink.rewrite(undoWhole.inputStream()); sink.force() }
            return EditResult.Failure("rewrite error: ${t.message}")
        }
    }
}
