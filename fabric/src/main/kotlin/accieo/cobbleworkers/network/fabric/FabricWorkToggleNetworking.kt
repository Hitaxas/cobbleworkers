/*
 * Copyright (C) 2026 HitaxasTV
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package accieo.cobbleworkers.network.fabric

import accieo.cobbleworkers.network.payloads.ToggleWorkPayload
import accieo.cobbleworkers.utilities.CobbleworkersWorkToggle
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking

object FabricWorkToggleNetworking {

    fun register() {
        ServerPlayNetworking.registerGlobalReceiver(ToggleWorkPayload.ID) { payload, context ->
            context.server().execute {
                val player = context.player()
                val world = player.serverWorld

                val entity = world.iterateEntities()
                    .firstOrNull { it.uuid == payload.pokemonId }

                val pokemonEntity = entity as? PokemonEntity ?: return@execute

                if (pokemonEntity.ownerUuid != player.uuid) return@execute

                val pokemon = pokemonEntity.pokemon
                val newState = !CobbleworkersWorkToggle.canWork(pokemon)

                CobbleworkersWorkToggle.setCanWork(pokemon, newState)
            }
        }
    }
}
