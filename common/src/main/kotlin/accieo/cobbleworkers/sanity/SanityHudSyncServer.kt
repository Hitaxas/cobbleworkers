/*
 * Copyright (C) 2025 HitaxasTV
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package accieo.cobbleworkers.sanity

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.entity.Entity

object SanityHudSyncServer {

    private const val UPDATE_INTERVAL = 20L // once per second
    private val lastUpdate = mutableMapOf<ServerPlayerEntity, Long>()

    fun tick(player: ServerPlayerEntity) {
        val now = player.serverWorld.time
        val last = lastUpdate[player] ?: 0L
        if (now - last < UPDATE_INTERVAL) return
        lastUpdate[player] = now

        sendUpdate(player)
    }

    fun sendUpdate(player: ServerPlayerEntity) {
        val world = player.world as? ServerWorld ?: return
        val list = mutableListOf<SanityEntry>()

        val entities = world.iterateEntities()

        val radius = 16.0 // pasture radius or tweak

        for (entity in (entities as Iterable<Entity>)) {
            if (entity is PokemonEntity && entity.owner == player) {

                // Only show Pokémon near the player
                if (entity.squaredDistanceTo(player) > radius * radius)
                    continue

                list += SanityEntry(
                    entity.pokemon.uuid,
                    entity.pokemon.getDisplayName().string,
                    SanityManager.getSanityPercent(entity),
                    SanityManager.getStatus(entity)
                )
            }
        }

        // If no pokemon nearby → sends empty list → HUD hides
        SanityPlatformNetworkingInstance.sendSanityUpdate(player, list)
    }
}
