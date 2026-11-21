package com.imsbooks.songbook.util

/**
 * Mobile numbers, mirroring the backend's PhoneNumbers and the web app's utils/mobile.ts: sent as
 * E.164 (+919876543210). A 10-digit Indian mobile (starting 6-9), optionally prefixed with 0 or 91,
 * gets +91; any other country needs its "+" code. Spaces, dashes, dots and brackets are ignored.
 */
object Mobile {
    private val INDIAN = Regex("^[6-9]\\d{9}$")
    private val E164 = Regex("^\\+[1-9]\\d{7,14}$")
    private val SEPARATORS = Regex("[\\s\\-().]")

    const val HELP = "10-digit mobile number. Outside India, start with + and the country code."

    fun normalize(raw: String): String? {
        var s = raw.trim().replace(SEPARATORS, "")
        if (s.startsWith("00")) s = "+" + s.substring(2)
        if (!s.startsWith("+")) {
            if (s.startsWith("0") && s.length == 11) s = s.substring(1)
            else if (s.startsWith("91") && s.length == 12) s = s.substring(2)
            if (!INDIAN.matches(s)) return null
            s = "+91$s"
        }
        if (s.startsWith("+91") && !INDIAN.matches(s.substring(3))) return null
        return if (E164.matches(s)) s else null
    }

    /** "+919876543210" -> "+91 98765 43210"; other countries are shown as stored. */
    fun format(e164: String?): String = when {
        e164.isNullOrEmpty() -> ""
        e164.startsWith("+91") && e164.length == 13 -> "+91 ${e164.substring(3, 8)} ${e164.substring(8)}"
        else -> e164
    }
}

/** Password policy, checked again server-side: 10-128 characters, not the mobile number. */
fun passwordProblem(password: String, mobile: String?): String? = when {
    password.length < 10 -> "Use at least 10 characters."
    password.length > 128 -> "Use at most 128 characters."
    mobile != null && password.trim().equals(mobile.trim(), ignoreCase = true) ->
        "Password must not be your mobile number."
    else -> null
}

fun isCompleteCode(code: String) = Regex("^\\d{6}$").matches(code)
