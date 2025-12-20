/*
 * Copyright (C) 2025 HitaxasTV
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package accieo.cobbleworkers.sanity

import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld

object SanitySyncTicker {
    private const val SYNC_INTERVAL = 20L // Sync every second (20 ticks)
    private var tickCounter = 0L

    fun tick(world: ServerWorld) {
        tickCounter++
        
        if (tickCounter % SYNC_INTERVAL == 0L) {
            // Sync for all players in this world
            world.players.forEach { player ->
                if (player is ServerPlayerEntity) {
                    SanityHudSyncServer.sendUpdate(player)
                }
            }
        }
    }
}