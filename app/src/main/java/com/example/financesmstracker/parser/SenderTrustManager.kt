package com.example.financesmstracker.parser

enum class SenderTrustStatus {
    TRUSTED,
    UNTRUSTED,
    UNKNOWN
}

object SenderTrustManager {
    /*
     * Sender triage is intentionally separate from transaction parsing.
     * The structure is adapted from Umber (MIT), DeepakSilaych/umber:
     * https://github.com/DeepakSilaych/umber
     *
     * A sender being trusted is only one signal. The message still has to pass
     * the transaction parser and the receiver's confidence threshold.
     */
    private val BANK_CODES = setOf(
        "hdfcbk", "hdfcbn", "sbiinb", "sbiupi", "sbibnk", "sbicrd", "atmsbi",
        "icicib", "icicit", "axisbk", "axisbn", "kotakb", "kotak",
        "pnbsms", "pnbbnk", "bobtxn", "bobibn", "canbnk", "cbssbi",
        "unionb", "ubinet", "idfcfb", "yesbnk", "indusb", "aubank",
        "rblbnk", "fedbnk", "citibk", "hsbcin", "scbank", "idbibk",
        "bankin", "cbinbk", "iobchn", "ucobnk", "psbbnk", "dcbbnk",
        "equtas", "esafbk", "jkbank", "karbnk", "kvbank", "tmbank",
        "amexin", "onecrd", "slcebk"
    )

    private val PSP_CODES = setOf(
        "paytmb", "paytm", "phonpe", "phnpay", "gpayin", "bhimup", "npcibh",
        "amzonp", "amazon", "mobikw", "freechg", "cred", "slice", "jupitr",
        "fisdom", "razrpy", "cashfr"
    )

    private val PERSONAL_NUMBER = Regex("""^(?:\+?91|0)?[6-9]\d{9}$""")
    private val NON_ALNUM = Regex("""[^a-z0-9]""")

    private val REJECT_OTP = Regex(
        """\botp\b|one[\s-]?time[\s-]?password|verification code|\bcvv\b""",
        RegexOption.IGNORE_CASE
    )

    private val REJECT_PROMO = Regex(
        """pre[\s-]?approved|apply now|click here|hurry|limited period|offer ends|""" +
            """t&c apply|download the app|you have won|congratulations|lowest interest|""" +
            """upgrade your|refer and earn|reward points|cashback offer|special offer|""" +
            """limited period offer|annual fee waiver|annual fee.{0,40}\bspends?\b|""" +
            """\bspends?\s+(?:of|rs\.?|inr|₹)|\b(?:get|earn|save)\b.{0,40}\b(?:cashback|reward|bonus|points)\b""",
        RegexOption.IGNORE_CASE
    )

    private val REJECT_NOT_COMPLETED = Regex(
        """will be (?:debited|deducted|credited|charged|transferred|reversed|refunded|blocked|processed)|""" +
            """is due|due on|due date|(?:collect|payment|money) request|has requested|requesting|""" +
            """(?:failed|declined|unsuccessful|not processed|could not be processed)|""" +
            """\bnot (?:debited|credited|deducted|charged)\b|\brejection\b|""" +
            """to (?:authorise|authorize|approve)|\bscheduled\b|\bpending\b""",
        RegexOption.IGNORE_CASE
    )

    private val REJECT_INFO_ONLY = Regex(
        """mini statement|statement is ready|statement has been generated|e-statement|""" +
            """available balance|available limit|credit limit""",
        RegexOption.IGNORE_CASE
    )

    fun normalizeSender(sender: String): String =
        NON_ALNUM.replace(sender.trim().lowercase(), "")

    fun classifySender(sender: String): SenderTrustStatus {
        val raw = sender.trim()
        if (raw.isEmpty() || raw.equals("UNKNOWN", ignoreCase = true)) {
            return SenderTrustStatus.UNKNOWN
        }

        val compact = normalizeSender(raw)
        if (PERSONAL_NUMBER.matches(compact)) {
            return SenderTrustStatus.UNTRUSTED
        }

        val segments = raw.lowercase()
            .split('-', '_', '.')
            .map { NON_ALNUM.replace(it, "") }
            .filter { it.isNotEmpty() }

        val candidates = segments + compact

        if (candidates.any { candidate -> BANK_CODES.contains(candidate) || BANK_CODES.any { candidate.contains(it) } }) {
            return SenderTrustStatus.TRUSTED
        }

        if (candidates.any { candidate -> PSP_CODES.contains(candidate) || PSP_CODES.any { candidate.contains(it) } }) {
            return SenderTrustStatus.TRUSTED
        }

        // Preserve the old behavior for familiar human-readable sender names.
        val upper = raw.uppercase()
        if (listOf("HDFC", "AXIS", "ICICI", "SBI", "KOTAK", "PAYTM", "PHONEPE", "HDFCBK", "AXISBK", "ICICIB", "SBICARD")
            .any { upper.contains(it) }) {
            return SenderTrustStatus.TRUSTED
        }

        if (upper.matches(Regex("^[A-Z]{2}-[A-Z0-9]+(?:-[A-Z0-9]+)?$"))) {
            return SenderTrustStatus.UNKNOWN
        }

        return SenderTrustStatus.UNTRUSTED
    }

    /**
     * Hard rejection for messages that describe an offer, OTP, future/requested payment,
     * failed/pending operation, or informational balance/statement event.
     *
     * This runs before bank-specific parsers so a trusted sender cannot accidentally turn a
     * promotional message into a ledger transaction.
     */
    fun isNonTransactionalFinancialMessage(messageBody: String): Boolean {
        if (messageBody.isBlank()) return true

        if (REJECT_OTP.containsMatchIn(messageBody) ||
            REJECT_PROMO.containsMatchIn(messageBody) ||
            REJECT_NOT_COMPLETED.containsMatchIn(messageBody)
        ) {
            return true
        }

        // "Available balance/limit" is often appended to a real transaction SMS.
        // Reject it only when the message contains no actual transaction event.
        if (REJECT_INFO_ONLY.containsMatchIn(messageBody)) {
            val lower = messageBody.lowercase()
            val hasTransactionEvent =
                lower.contains("debited") || lower.contains("credited") ||
                    lower.contains("spent") || lower.contains("paid") ||
                    lower.contains("received") || lower.contains("transferred") ||
                    lower.contains("withdrawn") || lower.contains("purchase") ||
                    lower.contains("charged") || lower.contains("deducted")

            if (!hasTransactionEvent) return true
        }

        return false
    }

    fun isFinancialLooking(messageBody: String): Boolean {
        if (isNonTransactionalFinancialMessage(messageBody)) return false

        val lower = messageBody.lowercase()
        val hasTransactionKeyword =
            lower.contains("debited") || lower.contains("credited") ||
            lower.contains("spent") || lower.contains("paid") ||
            lower.contains("received") || lower.contains("transfer") ||
            lower.contains("transferred") || lower.contains("withdrawn") ||
            lower.contains("purchase") || lower.contains("payment") ||
            lower.contains("a/c") || lower.contains("account") || lower.contains("card")

        val hasAmount = Regex(
            """(?:inr|rs\.?|₹|sgd|usd|eur|gbp)\s*[0-9]"""
        ).containsMatchIn(lower)

        return hasTransactionKeyword && hasAmount
    }
}
