/*
 * Copyright (C) 2026 HitaxasTV
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package accieo.cobbleworkers.network.neoforge

import accieo.cobbleworkers.network.payloads.ToggleWorkPayload
import accieo.cobbleworkers.utilities.CobbleworkersWorkToggle
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.handling.IPayloadContext
import net.neoforged.neoforge.network.registration.PayloadRegistrar

@EventBusSubscriber(
    modid = "cobbleworkers",
    bus = EventBusSubscriber.Bus.MOD
)
object NeoForgeWorkToggleNetworking {

    @SubscribeEvent
    fun registerPayloads(event: net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent) {
        val registrar: PayloadRegistrar = event.registrar("1.0")

        registrar.playToServer(
            ToggleWorkPayload.ID,
            ToggleWorkPayload.CODEC
        ) { payload: ToggleWorkPayload, context: IPayloadContext ->
            val player = context.player() as? ServerPlayerEntity ?: return@playToServer

            val serverWorld = player.world as? ServerWorld ?: return@playToServer
            val entity = serverWorld.getEntity(payload.pokemonId)

            if (entity is PokemonEntity && entity.ownerUuid == player.uuid) {
                val current = CobbleworkersWorkToggle.canWork(entity.pokemon)
                CobbleworkersWorkToggle.setCanWork(entity.pokemon, !current)
            }
        }
    }

    fun sendToggle(uuid: java.util.UUID) {
        PacketDistributor.sendToServer(ToggleWorkPayload(uuid))
    }
}