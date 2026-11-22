package com.voicepick.photos

import java.time.LocalDate
import java.time.Month
import java.time.MonthDay
import java.time.YearMonth

/** A span of days, both ends inclusive. */
data class Period(val label: String, val start: LocalDate, val end: LocalDate)

/**
 * Turns a spoken or typed period into dates, in Greek or English:
 *  - holidays: Πάσχα, Χριστούγεννα, Πρωτοχρονιά, Απόκριες, Καθαρά Δευτέρα, Αγίου Πνεύματος, Δεκαπενταύγουστος
 *  - seasons:  Καλοκαίρι, Φθινόπωρο, Χειμώνας, Άνοιξη
 *  - months:   Μάιος 2025, Αυγούστου, ...;  single days: 15 Αυγούστου 2024
 *  - years:    2024;  relative: φέτος, πέρσι, προπέρσι, σήμερα, χθες
 * Without a year, the most recent occurrence that has already started is used.
 */
object PeriodParser {

    private enum class Kind { EASTER, CHRISTMAS, NEW_YEAR, CARNIVAL, CLEAN_MONDAY, WHIT_MONDAY, AUGUST_15, SUMMER, AUTUMN, WINTER, SPRING }

    fun parse(input: String, today: LocalDate = LocalDate.now()): Period? {
        val text = KeywordMatcher.normalize(input)
        val tokens = text.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        val label = input.trim().replaceFirstChar { it.titlecase() }

        // Relative days.
        if (tokens.any { it == "σημερα" || it == "today" }) return Period(label, today, today)
        if (tokens.any { it == "χθες" || it == "χτες" || it == "yesterday" }) {
            val d = today.minusDays(1)
            return Period(label, d, d)
        }

        val explicitYear: Int? = tokens.firstOrNull { it.length == 4 && it.all(Char::isDigit) && it.toInt() in 1900..2100 }?.toInt()
            ?: when {
                tokens.any { it == "προπερσι" || it == "προπερυσι" } -> today.year - 2
                tokens.any { it == "περσι" || it == "περυσι" } || ("last" in tokens && "year" in tokens) -> today.year - 1
                tokens.any { it == "φετος" } || ("this" in tokens && "year" in tokens) -> today.year
                else -> null
            }

        val kind = findKind(tokens, text)
        val monthIndex = tokens.indexOfFirst { monthOf(it) != null }

        // Resolves a year-dependent period, picking the latest one that has started if no year was said.
        fun resolve(build: (Int) -> Pair<LocalDate, LocalDate>): Period {
            val year = explicitYear ?: if (build(today.year).first <= today) today.year else today.year - 1
            val (s, e) = build(year)
            return Period(label, s, e)
        }

        if (kind != null) return resolve { y -> range(kind, y) }

        if (monthIndex >= 0) {
            val month = monthOf(tokens[monthIndex])!!
            val day = listOfNotNull(tokens.getOrNull(monthIndex - 1), tokens.getOrNull(monthIndex + 1))
                .firstOrNull { it.length <= 2 && it.all(Char::isDigit) && it.toInt() in 1..31 }?.toInt()
            return if (day != null) {
                resolve { y ->
                    val d = MonthDay.of(month, day.coerceAtMost(month.maxLength())).atYear(y)
                    d to d
                }
            } else {
                resolve { y -> YearMonth.of(y, month).let { it.atDay(1) to it.atEndOfMonth() } }
            }
        }

        if (explicitYear != null) {
            return Period(label, LocalDate.of(explicitYear, 1, 1), LocalDate.of(explicitYear, 12, 31))
        }
        return null
    }

    private fun findKind(tokens: List<String>, text: String): Kind? {
        fun has(vararg prefixes: String) = tokens.any { t -> prefixes.any { t.startsWith(it) } }
        return when {
            has("πασχα", "λαμπρη", "easter") -> Kind.EASTER
            has("χριστουγενν", "christmas", "xmas") -> Kind.CHRISTMAS
            has("πρωτοχρονι") || "new year" in text -> Kind.NEW_YEAR
            has("καθαρα", "καθαρη") || "clean monday" in text -> Kind.CLEAN_MONDAY
            has("αποκρι", "καρναβαλ", "carnival") -> Kind.CARNIVAL
            has("πνευματ", "whit") -> Kind.WHIT_MONDAY
            has("δεκαπενταυγουστ", "κοιμησ") -> Kind.AUGUST_15
            has("καλοκαιρ", "summer") -> Kind.SUMMER
            has("φθινοπωρ", "autumn", "fall") -> Kind.AUTUMN
            has("χειμων", "winter") -> Kind.WINTER
            has("ανοιξ", "spring") -> Kind.SPRING
            else -> null
        }
    }

    private fun range(kind: Kind, y: Int): Pair<LocalDate, LocalDate> = when (kind) {
        Kind.EASTER -> orthodoxEaster(y).let { it.minusDays(7) to it.plusDays(7) }
        // From Τσικνοπέμπτη to Καθαρά Δευτέρα.
        Kind.CARNIVAL -> orthodoxEaster(y).minusDays(48).let { it.minusDays(11) to it }
        Kind.CLEAN_MONDAY -> orthodoxEaster(y).minusDays(48).let { it.minusDays(2) to it }
        Kind.WHIT_MONDAY -> orthodoxEaster(y).let { it.plusDays(48) to it.plusDays(50) }
        Kind.CHRISTMAS -> LocalDate.of(y, 12, 20) to LocalDate.of(y + 1, 1, 7)
        Kind.NEW_YEAR -> LocalDate.of(y - 1, 12, 31) to LocalDate.of(y, 1, 1)
        Kind.AUGUST_15 -> LocalDate.of(y, 8, 13) to LocalDate.of(y, 8, 17)
        Kind.SUMMER -> LocalDate.of(y, 6, 1) to LocalDate.of(y, 9, 30)
        Kind.AUTUMN -> LocalDate.of(y, 10, 1) to LocalDate.of(y, 11, 30)
        Kind.WINTER -> LocalDate.of(y - 1, 12, 1) to YearMonth.of(y, 2).atEndOfMonth()
        Kind.SPRING -> LocalDate.of(y, 3, 1) to LocalDate.of(y, 5, 31)
    }

    /** Greek Orthodox Easter Sunday (Meeus' Julian algorithm, converted to Gregorian; valid 1900–2099). */
    fun orthodoxEaster(year: Int): LocalDate {
        val a = year % 4
        val b = year % 7
        val c = year % 19
        val d = (19 * c + 15) % 30
        val e = (2 * a + 4 * b - d + 34) % 7
        val month = (d + e + 114) / 31
        val day = (d + e + 114) % 31 + 1
        return LocalDate.of(year, month, day).plusDays(13)
    }

    private val GREEK_MONTHS = listOf(
        Month.JANUARY to listOf("ιανουαρ", "γεναρ"),
        Month.FEBRUARY to listOf("φεβρουαρ", "φλεβαρ"),
        Month.MARCH to listOf("μαρτ"),
        Month.APRIL to listOf("απριλ"),
        Month.MAY to listOf("μαιος", "μαιου", "μαιο", "μαης", "μαη"),
        Month.JUNE to listOf("ιουν"),
        Month.JULY to listOf("ιουλ"),
        Month.AUGUST to listOf("αυγουστ"),
        Month.SEPTEMBER to listOf("σεπτεμβρ", "σεπτεβρ", "σεπτεμρ"),
        Month.OCTOBER to listOf("οκτωβρ", "οχτωβρ", "οκτωμβρ"),
        Month.NOVEMBER to listOf("νοεμβρ", "νοεβρ"),
        Month.DECEMBER to listOf("δεκεμβρ", "δεκεβρ"),
    )
    private val EXACT_ONLY = setOf("μαιος", "μαιου", "μαιο", "μαης", "μαη")

    private fun monthOf(token: String): Month? {
        GREEK_MONTHS.forEach { (m, stems) ->
            if (stems.any { if (it in EXACT_ONLY) token == it else token.startsWith(it) }) return m
        }
        if (token.length >= 3 && token.all { it in 'a'..'z' }) {
            Month.entries.forEach { m -> if (m.name.lowercase().startsWith(token)) return m }
        }
        return null
    }
}
