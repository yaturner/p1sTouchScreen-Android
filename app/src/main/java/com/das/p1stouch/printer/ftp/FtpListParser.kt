package com.das.p1stouch.printer.ftp

import java.util.Calendar

/** One entry from an FTP LIST response line. */
data class FtpEntry(val name: String, val sizeBytes: Long?, val modifiedEpochMillis: Long?)

/**
 * Parses one line of the printer's FTP `LIST` output. Port of the Python
 * app's _parse_ftp_list_entry/_parse_ftp_mtime -- confirmed against a real
 * printer that this returns raw Unix `ls -l`-style lines (not bare
 * filenames), e.g.:
 *   -rw-rw-rw- 1 root root 43745911 Nov 24 2025 0.16mm layer, 2 walls.3mf
 * Filenames may contain spaces, so the split is anchored on the 9 fixed
 * fields (perms, links, owner, group, size, month, day, year-or-time) and
 * everything after that is the filename verbatim. Do NOT trust a generic
 * Unix LIST parser (e.g. Commons Net's built-in one) without verifying
 * against this printer's actual output first -- confirmed field alignment
 * by testing this exact function against real output.
 */
object FtpListParser {
    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    fun parse(line: String): FtpEntry {
        val parts = line.split(Regex("\\s+"), limit = 9)
        if (parts.size == 9 && parts[0].isNotEmpty() && parts[0][0] in "-dl") {
            val sizeBytes = parts[4].toLongOrNull()
            val modified = parseMtime(parts[5], parts[6], parts[7])
            return FtpEntry(parts[8], sizeBytes, modified)
        }
        return FtpEntry(line.trim(), null, null)
    }

    // ls -l shows a time (HH:MM, current year implied) for recent files and
    // a year for older ones -- never both, so the field's shape tells us
    // which case we're in.
    private fun parseMtime(month: String, day: String, yearOrTime: String): Long? {
        val monthIndex = MONTHS.indexOf(month)
        if (monthIndex < 0) return null
        val dayNum = day.toIntOrNull() ?: return null
        val cal = Calendar.getInstance()
        return try {
            if (":" in yearOrTime) {
                val (hour, minute) = yearOrTime.split(":").map { it.toInt() }
                cal.set(cal.get(Calendar.YEAR), monthIndex, dayNum, hour, minute, 0)
            } else {
                val year = yearOrTime.toInt()
                cal.set(year, monthIndex, dayNum, 0, 0, 0)
            }
            cal.set(Calendar.MILLISECOND, 0)
            cal.timeInMillis
        } catch (e: Exception) {
            null
        }
    }
}
