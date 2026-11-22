package com.voicepick.photos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class PeriodParserTest {
    private val today = LocalDate.of(2026, 11, 22)
    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)

    private fun check(input: String, start: LocalDate, end: LocalDate) {
        val p = PeriodParser.parse(input, today) ?: throw AssertionError("could not parse «$input»")
        assertEquals("start of «$input»", start, p.start)
        assertEquals("end of «$input»", end, p.end)
    }

    @Test fun orthodoxEasterDates() {
        assertEquals(d(2023, 4, 16), PeriodParser.orthodoxEaster(2023))
        assertEquals(d(2024, 5, 5), PeriodParser.orthodoxEaster(2024))
        assertEquals(d(2025, 4, 20), PeriodParser.orthodoxEaster(2025))
        assertEquals(d(2026, 4, 12), PeriodParser.orthodoxEaster(2026))
        assertEquals(d(2027, 5, 2), PeriodParser.orthodoxEaster(2027))
    }

    @Test fun easterIsPlusMinusOneWeek() {
        check("Πάσχα 2024", d(2024, 4, 28), d(2024, 5, 12))
        check("easter 2025", d(2025, 4, 13), d(2025, 4, 27))
        check("Πάσχα", d(2026, 4, 5), d(2026, 4, 19)) // no year → latest that has started
    }

    @Test fun seasons() {
        check("Καλοκαίρι 2026", d(2026, 6, 1), d(2026, 9, 30))
        check("ΚΑΛΟΚΑΙΡΙ 2024", d(2024, 6, 1), d(2024, 9, 30))
        check("φθινόπωρο 2025", d(2025, 10, 1), d(2025, 11, 30))
        check("Χειμώνας 2025", d(2024, 12, 1), d(2025, 2, 28))
        check("Άνοιξη 2024", d(2024, 3, 1), d(2024, 5, 31))
        check("summer 2024", d(2024, 6, 1), d(2024, 9, 30))
        check("πέρσι το καλοκαίρι", d(2025, 6, 1), d(2025, 9, 30))
    }

    @Test fun holidays() {
        check("Χριστούγεννα 2024", d(2024, 12, 20), d(2025, 1, 7))
        check("Χριστούγεννα", d(2025, 12, 20), d(2026, 1, 7)) // Christmas 2026 hasn't started yet
        check("Πρωτοχρονιά 2025", d(2024, 12, 31), d(2025, 1, 1))
        check("Απόκριες 2025", d(2025, 2, 20), d(2025, 3, 3))
        check("Καθαρά Δευτέρα 2025", d(2025, 3, 1), d(2025, 3, 3))
        check("Δεκαπενταύγουστος 2023", d(2023, 8, 13), d(2023, 8, 17))
    }

    @Test fun monthsAndDays() {
        check("Μάιος 2025", d(2025, 5, 1), d(2025, 5, 31))
        check("Μαΐου 2025", d(2025, 5, 1), d(2025, 5, 31))
        check("Φλεβάρης 2024", d(2024, 2, 1), d(2024, 2, 29))
        check("Δεκέμβριος", d(2025, 12, 1), d(2025, 12, 31))
        check("Οκτώβριος", d(2026, 10, 1), d(2026, 10, 31))
        check("March 2024", d(2024, 3, 1), d(2024, 3, 31))
        check("15 Αυγούστου 2024", d(2024, 8, 15), d(2024, 8, 15))
    }

    @Test fun yearsAndRelative() {
        check("2023", d(2023, 1, 1), d(2023, 12, 31))
        check("φέτος", d(2026, 1, 1), d(2026, 12, 31))
        check("σήμερα", today, today)
        check("χθες", d(2026, 11, 21), d(2026, 11, 21))
    }

    @Test fun keepsSpokenLabel() {
        assertEquals("Πάσχα 2024", PeriodParser.parse("πάσχα 2024", today)?.label)
    }

    @Test fun rejectsNonsense() {
        assertNull(PeriodParser.parse("γεια σου", today))
        assertNull(PeriodParser.parse("", today))
    }
}
