package org.civicrelay.app

/** Minimal phone-number normalization; carrier/SMS app decides final reachability. */
object SmsContactPolicy {
    fun normalize(input: String): String {
        val compact = input.trim().filterNot { it.isWhitespace() || it == '(' || it == ')' || it == '-' }
        require(Regex("""\+?[0-9]{7,15}""").matches(compact)) {
            "Use a 7-15 digit phone number, optionally starting with +country code"
        }
        return compact
    }
}
