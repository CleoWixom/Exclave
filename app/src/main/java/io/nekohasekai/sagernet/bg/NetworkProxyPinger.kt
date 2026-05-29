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

import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.TunImplementation
import io.nekohasekai.sagernet.bg.test.V2RayTestInstance
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

object NetworkProxyPinger {

    suspend fun pingAndRank(
        candidates: List<ProxyEntity>,
        testUrl: String  = DataStore.connectionTestURL,
        timeoutMs: Int   = DataStore.autoPingTimeout.takeIf { it > 0 } ?: 3000,
        concurrency: Int = DataStore.autoPingConcurrency.takeIf { it > 0 } ?: 4,
    ): List<ProxyEntity> = coroutineScope {
        val results = ConcurrentHashMap<Long, Int>()
        val semaphore = Semaphore(concurrency)

        val protectPath =
            if (DataStore.tunImplementation == TunImplementation.SYSTEM
                && DataStore.serviceMode == Key.MODE_VPN
                && SagerNet.started
                && DataStore.startedProfile > 0
            ) SagerNet.deviceStorage.noBackupFilesDir.toString() + "/protect_path"
            else ""

        candidates.map { proxy ->
            launch {
                semaphore.withPermit {
                    val ping = try {
                        val instance = if (protectPath.isNotEmpty())
                            V2RayTestInstance(proxy, testUrl, timeoutMs, protectPath)
                        else
                            V2RayTestInstance(proxy, testUrl, timeoutMs)
                        instance.use { it.doTest() }
                    } catch (_: Exception) {
                        Int.MAX_VALUE
                    }
                    results[proxy.id] = ping
                    proxy.ping   = if (ping == Int.MAX_VALUE) 0 else ping
                    proxy.status = if (ping == Int.MAX_VALUE) 3 else 1
                    SagerDatabase.proxyDao.updateProxy(proxy)
                }
            }
        }.joinAll()

        candidates.sortedBy { results[it.id] ?: Int.MAX_VALUE }
    }
}
