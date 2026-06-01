package com.forensics.core.app

/** A non-destructive preview of what an edit WOULD do, for UI badges and consent dialogs. */
sealed interface EditClassification {
    /** A pure same-length in-place byte patch (cheap, safe). */
    data object InPlace : EditClassification

    /** Applying the edit requires rebuilding the whole file in place ([reason] explains why). */
    data class Rewrite(val reason: String) : EditClassification

    /** The edit cannot be performed ([reason] explains why). */
    data class Rejected(val reason: String) : EditClassification
}
