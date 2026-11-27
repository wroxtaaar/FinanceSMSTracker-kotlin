package com.example.financesmstracker.util

/** Indian digit grouping (12,34,567.89), independent of the phone's locale. Pure Kotlin so it is unit tested on the JVM. */
object IndianMoney {
    /** Amount without symbol or sign: 15292205 -> "1,52,922.05". */
    fun group(minorAbs: Long): String {
        val whole = (minorAbs / 100).toString()
        val fraction = (minorAbs % 100).toString().padStart(2, '0')
        val grouped = if (whole.length <= 3) {
            whole
        } else {
            val head = whole.dropLast(3)
            val tail = whole.takeLast(3)
            head.reversed().chunked(2).joinToString(",").reversed() + "," + tail
        }
        return "$grouped.$fraction"
    }

    /** "₹1,52,922.05"; negative amounts read "-₹1,000.00". */
    fun format(minor: Long, symbol: String = "₹"): String =
        (if (minor < 0) "-" else "") + symbol + group(if (minor < 0) -minor else minor)

    /** A list amount with an explicit direction: "+₹900.00" for money in, "-₹900.00" for money out. */
    fun signed(minorAbs: Long, credit: Boolean, symbol: String = "₹"): String =
        (if (credit) "+" else "-") + symbol + group(if (minorAbs < 0) -minorAbs else minorAbs)
}
