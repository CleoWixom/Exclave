/*******************************************************************************
 *                                                                             *
 * Copyright (C) 2024  dyhkwong                                                *
 *                                                                             *
 * This program is free software: you can redistribute it and/or modify        *
 * it under the terms of the GNU General Public License as published by        *
 * the Free Software Foundation, either version 3 of the License, or           *
 *  (at your option) any later version.                                        *
 *                                                                             *
 * This program is distributed in the hope that it will be useful,             *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of              *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the               *
 * GNU General Public License for more details.                                *
 *                                                                             *
 * You should have received a copy of the GNU General Public License           *
 * along with this program. If not, see <https://www.gnu.org/licenses/>.       *
 *                                                                             *
 ******************************************************************************/

package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.database.ProxyEntity

object KeywordFilter {

    fun parse(raw: String): List<String> =
        raw.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    fun matches(proxy: ProxyEntity, keywords: List<String>): Boolean {
        if (keywords.isEmpty()) return true
        val name = proxy.displayName().lowercase()
        val type = proxy.displayType().lowercase()
        return keywords.any { kw -> name.contains(kw) || type.contains(kw) }
    }

    /**
     * Candidate selection logic:
     *  - Both empty         → all proxies
     *  - currentKw non-empty → filter by currentKw
     *  - currentKw empty,
     *    otherKw non-empty  → all proxies EXCEPT those matching otherKw
     */
    fun candidates(
        all: List<ProxyEntity>,
        currentRaw: String,
        otherRaw: String,
    ): List<ProxyEntity> {
        val currentKw = parse(currentRaw)
        val otherKw   = parse(otherRaw)
        return when {
            currentKw.isEmpty() && otherKw.isEmpty() -> all
            currentKw.isNotEmpty()                   -> all.filter { matches(it, currentKw) }
            else                                     -> all.filterNot { matches(it, otherKw) }
        }
    }
}
