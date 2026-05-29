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

import android.net.Network
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs

object NetworkAwareSelector {

    suspend fun onNetworkChanged(network: Network?) {
        if (!DataStore.autoSelectByNetwork) return
        if (network == null) return

        val netType = SagerNet.currentNetworkType
        if (netType != "wifi" && netType != "data") return

        val allProxies = SagerDatabase.proxyDao.getByGroup(DataStore.currentGroup().id)
        if (allProxies.isEmpty()) return

        val isWifi = netType == "wifi"
        val candidates = KeywordFilter.candidates(
            all        = allProxies,
            currentRaw = if (isWifi) DataStore.wifiKeywords else DataStore.mobileKeywords,
            otherRaw   = if (isWifi) DataStore.mobileKeywords else DataStore.wifiKeywords,
        )

        if (candidates.isEmpty()) {
            Logs.w("NetworkAwareSelector: no candidates for netType=$netType")
            return
        }

        val ranked = if (DataStore.autoPingBeforeConnect) {
            NetworkProxyPinger.pingAndRank(candidates)
        } else {
            candidates.sortedWith(compareBy {
                if (it.status == 1 && it.ping > 0) it.ping else Int.MAX_VALUE
            })
        }

        val best = ranked.firstOrNull { it.status != 3 } ?: run {
            Logs.w("NetworkAwareSelector: all candidates unreachable for netType=$netType")
            return
        }

        if (DataStore.selectedProxy == best.id) return

        DataStore.selectedProxy = best.id
        Logs.i("NetworkAwareSelector: → '${best.displayName()}' ping=${best.ping}ms netType=$netType")
        SagerNet.reloadService()
    }
}
