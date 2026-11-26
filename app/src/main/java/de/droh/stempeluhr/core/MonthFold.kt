package de.droh.stempeluhr.core

import java.time.YearMonth

/** Welche Monate im Verlauf zugeklappt sind. */
object MonthFold {
    /**
     * Ohne eigene Wahl ist nur der neueste Monat aufgeklappt – so stehen die Summen aller Monate direkt untereinander.
     * Was man selbst auf- oder zugeklappt hat ([choices]: true = zugeklappt), bleibt so.
     */
    fun isCollapsed(month: YearMonth, newest: YearMonth?, choices: Map<YearMonth, Boolean>): Boolean =
        choices[month] ?: (month != newest)

    /** Alle Monate auf einmal auf- oder zuklappen. */
    fun all(months: Collection<YearMonth>, collapsed: Boolean): Map<YearMonth, Boolean> = months.associateWith { collapsed }

    fun parse(entries: Set<String>): Map<YearMonth, Boolean> = entries.mapNotNull { e ->
        val parts = e.split('=', limit = 2)
        if (parts.size == 2) runCatching { YearMonth.parse(parts[0]) to (parts[1] == "1") }.getOrNull() else null
    }.toMap()

    fun format(choices: Map<YearMonth, Boolean>): Set<String> = choices.map { (m, c) -> "$m=${if (c) 1 else 0}" }.toSet()
}
