package hu.orajegyzet.data

import java.time.LocalDate
import java.time.format.DateTimeFormatter

object NoteCodeGenerator {

    /**
     * Generál egy egyedi azonosító kódot a jegyzetnek.
     * Pl.: PROJ-001-20260910-EML-001 vagy MAT-20260911-JEG-001
     */
    fun generate(
        projectCode: String?,
        subject: String,
        docType: String = "JEG",
        date: LocalDate = LocalDate.now(),
        sequence: Int = 1
    ): String {
        val dateStr = date.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
        val prefix = if (!projectCode.isNullOrBlank()) {
            val clean = projectCode.replace("-", "").uppercase()
            if (clean.length <= 4) "PROJ-$clean" else clean
        } else {
            val clean = subject.trim().take(3).uppercase()
            if (clean.length < 3) "JEGY" else clean
        }
        val typeCode = docType.uppercase().take(3)
        val seqStr = "%03d".format(sequence)
        return "$prefix-$dateStr-$typeCode-$seqStr"
    }
}
