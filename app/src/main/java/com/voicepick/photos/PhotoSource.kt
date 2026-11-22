package com.voicepick.photos

import java.time.LocalDate

/** Which photos the user browses. */
sealed interface PhotoSource {
    data object All : PhotoSource

    /** Photos taken between [start] and [end] (both inclusive). [label] is e.g. "Πάσχα 2024". */
    data class Dates(val start: LocalDate, val end: LocalDate, val label: String?) : PhotoSource

    /** Photos in one album, by MediaStore RELATIVE_PATH. */
    data class Folder(val relPath: String) : PhotoSource
}
