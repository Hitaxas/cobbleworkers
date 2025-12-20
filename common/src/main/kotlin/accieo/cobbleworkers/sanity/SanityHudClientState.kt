/*
 * Copyright (C) 2025 HitaxasTV
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package accieo.cobbleworkers.sanity

import net.minecraft.client.MinecraftClient
import java.util.UUID

object SanityHudClientState {

    private data class SanityData(
        val entry: SanityEntry,
        val lastUpdateTime: Long,
        var displaySanity: Float // Interpolated value for smooth display
    )

    private val sanityMap: MutableMap<UUID, SanityData> = mutableMapOf()

    var sanityList: List<SanityEntry> = emptyList()
        private set

    fun update(entries: List<SanityEntry>) {
        val currentTime = System.currentTimeMillis()

        // Update or add new entries
        entries.forEach { entry ->
            val existing = sanityMap[entry.uuid]
            if (existing != null) {
                // Keep the current display value for interpolation
                sanityMap[entry.uuid] = SanityData(
                    entry = entry,
                    lastUpdateTime = currentTime,
                    displaySanity = existing.displaySanity
                )
            } else {
                // New entry - start at current value
                sanityMap[entry.uuid] = SanityData(
                    entry = entry,
                    lastUpdateTime = currentTime,
                    displaySanity = entry.sanity.toFloat()
                )
            }
        }

        // Remove entries that are no longer sent
        val currentUUIDs = entries.map { it.uuid }.toSet()
        sanityMap.keys.retainAll(currentUUIDs)

        sanityList = entries
    }

    fun tick() {
        val currentTime = System.currentTimeMillis()

        sanityMap.forEach { (uuid, data) ->
            val targetSanity = data.entry.sanity.toFloat()
            val timeSinceUpdate = (currentTime - data.lastUpdateTime) / 1000f

            // Interpolate towards target value
            val interpolationSpeed = 2.0f
            val delta = (targetSanity - data.displaySanity) * interpolationSpeed * 0.016f // Assuming ~60fps

            data.displaySanity += delta

            // Clamp to avoid overshooting
            data.displaySanity = data.displaySanity.coerceIn(0f, 100f)
        }
    }

    fun getDisplaySanity(uuid: UUID): Int {
        return sanityMap[uuid]?.displaySanity?.toInt() ?: 0
    }
}