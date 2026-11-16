package com.manuubett.nambalink

/** One person or business: a scanned card, a saved entry, or your own profile. */
class Person(
    val id: Long,
    val name: String,
    val org: String,
    val phone: String,       // normal form: +2547XXXXXXXX
    val till: String,
    val paybill: String,
    val acc: String
) {
    val hasPayment: Boolean get() = till.isNotEmpty() || paybill.isNotEmpty()

    // The name to show first
    val title: String
        get() = when {
            org.isNotEmpty() -> org
            name.isNotEmpty() -> name
            else -> "Unknown"
        }
}

/**
 * Phone number helpers and the QR contact card format.
 *
 * The QR holds a standard contact card (vCard). Any phone camera offers
 * "Add contact" for it, and NambaLink also reads the extra X-NAMBA lines
 * that carry the Till or Paybill details.
 */
object Namba {

    // 0712 345 678, 0112345678, +254 712 345 678, 254712345678, 712345678 -> +254712345678
    fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        val plus = trimmed.startsWith("+")
        val d = trimmed.filter { it.isDigit() }

        return when {
            d.startsWith("254") && d.length == 12 && (d[3] == '7' || d[3] == '1') -> "+$d"
            d.startsWith("0") && d.length == 10 && (d[1] == '7' || d[1] == '1') -> "+254" + d.substring(1)
            d.length == 9 && (d[0] == '7' || d[0] == '1') -> "+254$d"
            plus && d.length in 8..15 -> "+$d"
            else -> null
        }
    }

    // +254712345678 -> 0712 345 678
    fun pretty(phone: String): String {
        if (phone.startsWith("+254") && phone.length == 13) {
            val local = "0" + phone.substring(4)
            return local.substring(0, 4) + " " + local.substring(4, 7) + " " + local.substring(7)
        }

        return phone
    }

    private val kenyanNumber = Regex(
        "(?<![\\d])(?:\\+?254|0)[\\s\\-]?[17](?:[\\s\\-]?\\d){8}(?![\\d])"
    )

    // Every Kenyan number found in any text, each once, in order
    fun extractNumbers(text: String): List<String> {
        val found = LinkedHashSet<String>()

        for (m in kenyanNumber.findAll(text)) {
            val n = normalize(m.value)
            if (n != null) found.add(n)
        }

        if (found.isEmpty()) {
            val whole = normalize(text)
            if (whole != null) found.add(whole)
        }

        return found.toList()
    }

    // ---- Writing the QR contact card ----

    private fun esc(v: String): String =
        v.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n")

    fun vCard(p: Person): String {
        val sb = StringBuilder()
        val fn = if (p.name.isNotEmpty()) p.name else p.title

        sb.append("BEGIN:VCARD\r\n")
        sb.append("VERSION:3.0\r\n")
        sb.append("FN:").append(esc(fn)).append("\r\n")
        sb.append("N:;").append(esc(fn)).append(";;;\r\n")

        if (p.org.isNotEmpty()) sb.append("ORG:").append(esc(p.org)).append("\r\n")

        sb.append("TEL;TYPE=CELL:").append(p.phone).append("\r\n")

        if (p.till.isNotEmpty()) sb.append("X-NAMBA-TILL:").append(esc(p.till)).append("\r\n")
        if (p.paybill.isNotEmpty()) sb.append("X-NAMBA-PAYBILL:").append(esc(p.paybill)).append("\r\n")
        if (p.acc.isNotEmpty()) sb.append("X-NAMBA-ACC:").append(esc(p.acc)).append("\r\n")

        if (p.hasPayment) {
            val parts = ArrayList<String>()
            if (p.till.isNotEmpty()) parts.add("Till " + p.till)
            if (p.paybill.isNotEmpty()) {
                parts.add("Paybill " + p.paybill + (if (p.acc.isNotEmpty()) " Acc " + p.acc else ""))
            }
            sb.append("NOTE:Pay: ").append(esc(parts.joinToString(", "))).append("\r\n")
        }

        sb.append("END:VCARD")

        return sb.toString()
    }

    // ---- Reading what a QR holds ----

    private fun unesc(v: String): String =
        v.replace("\\n", "\n").replace("\\N", "\n").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")

    private fun parseVCard(text: String): Person? {
        if (!text.trimStart().startsWith("BEGIN:VCARD", ignoreCase = true)) return null

        // Long lines may be folded onto the next line
        val unfolded = text.replace("\r\n", "\n").replace("\r", "\n").replace(Regex("\n[ \t]"), "")

        var fn = ""
        var n = ""
        var org = ""
        var tel = ""
        var till = ""
        var bill = ""
        var acc = ""

        for (line in unfolded.split('\n')) {
            val idx = line.indexOf(':')
            if (idx < 0) continue

            val key = line.substring(0, idx).substringBefore(';').trim().uppercase()
            val value = unesc(line.substring(idx + 1)).trim()

            when (key) {
                "FN" -> fn = value
                "N" -> n = value.split(';').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
                "ORG" -> org = value.replace(";", " ").trim()
                "TEL" -> if (tel.isEmpty()) tel = value
                "X-NAMBA-TILL" -> till = value
                "X-NAMBA-PAYBILL" -> bill = value
                "X-NAMBA-ACC" -> acc = value
            }
        }

        val phone = normalize(tel) ?: tel.filter { it.isDigit() || it == '+' }
        if (phone.isEmpty()) return null

        val name = if (fn.isNotEmpty()) fn else n

        return Person(0L, name, org, phone, till, bill, acc)
    }

    private fun parseMeCard(text: String): Person? {
        if (!text.startsWith("MECARD:", ignoreCase = true)) return null

        var name = ""
        var tel = ""

        for (part in text.substring(7).split(';')) {
            val idx = part.indexOf(':')
            if (idx < 0) continue

            val key = part.substring(0, idx).uppercase()
            val value = part.substring(idx + 1).trim()

            if (key == "N") {
                // "Last,First" becomes "First Last"
                val bits = value.split(',').map { it.trim() }
                name = if (bits.size == 2) bits[1] + " " + bits[0] else value
            } else if (key == "TEL" && tel.isEmpty()) {
                tel = value
            }
        }

        val phone = normalize(tel) ?: tel.filter { it.isDigit() || it == '+' }
        if (phone.isEmpty()) return null

        return Person(0L, name.trim(), "", phone, "", "", "")
    }

    // A person from a contact QR, or a "tel:" link. Null when it is something else.
    fun parseCard(raw: String): Person? {
        val text = raw.trim()

        parseVCard(text)?.let { return it }
        parseMeCard(text)?.let { return it }

        if (text.startsWith("tel:", ignoreCase = true) || text.startsWith("smsto:", ignoreCase = true)) {
            val number = text.substringAfter(':').substringBefore('?').substringBefore(':')
            val phone = normalize(number) ?: number.filter { it.isDigit() || it == '+' }

            if (phone.isNotEmpty()) return Person(0L, "", "", phone, "", "", "")
        }

        return null
    }
}
