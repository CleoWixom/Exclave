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

package io.nekohasekai.sagernet.utils

import android.content.Context
import android.provider.Settings
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.uuid5
import java.util.UUID

object DeviceId {

    /**
     * Returns HWID by priority:
     * 1. Custom HWID from settings (if set)
     * 2. ANDROID_ID → normalized via uuid5()
     * 3. Fallback: random UUID, saved to DataStore for stability
     */
    fun get(context: Context): String {
        val custom = DataStore.customHwid
        if (custom.isNotBlank()) return custom.trim()

        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        )
        // "9774d56d682e549c" is a known default bug on some devices
        if (!androidId.isNullOrBlank() && androidId != "9774d56d682e549c") {
            return uuid5("exclave-hwid-$androidId")
        }

        var stored = DataStore.generatedHwid
        if (stored.isBlank()) {
            stored = UUID.randomUUID().toString()
            DataStore.generatedHwid = stored
        }
        return stored
    }
}
