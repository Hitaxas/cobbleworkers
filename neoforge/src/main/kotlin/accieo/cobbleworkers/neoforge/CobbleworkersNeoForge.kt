/*
 * Copyright (C) 2025 Accieo
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package accieo.cobbleworkers.neoforge

import accieo.cobbleworkers.Cobbleworkers
import accieo.cobbleworkers.registry.CobbleworkersBlocks
import accieo.cobbleworkers.integration.CobbleworkersIntegrationHandler
import accieo.cobbleworkers.neoforge.integration.NeoForgeIntegrationHelper
import accieo.cobbleworkers.sanity.SanityPlatformNetworkingInstance
import accieo.cobbleworkers.sanity.NeoForgeSanityNetworking
import accieo.cobbleworkers.sanity.SanitySyncPayload
import accieo.cobbleworkers.sanity.SanityHudClientState
import accieo.cobbleworkers.sanity.SanitySyncTicker
import net.neoforged.fml.common.Mod
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.handling.IPayloadContext
import net.neoforged.neoforge.event.tick.LevelTickEvent
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.minecraft.server.world.ServerWorld
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent
import net.minecraft.item.ItemGroups
import net.minecraft.item.ItemGroup
import accieo.cobbleworkers.commands.CobbleworkersCommands
import net.neoforged.neoforge.event.RegisterCommandsEvent


@Mod(Cobbleworkers.MODID)
object CobbleworkersNeoForge {
    init {
        SanityPlatformNetworkingInstance = NeoForgeSanityNetworking

        Cobbleworkers.init()
        CobbleworkersBlocks.register()

        MOD_BUS.addListener(::onCommonSetup)
        MOD_BUS.addListener(::onRegisterPayloads)
        MOD_BUS.addListener(::onBuildCreativeTabs) // Add this line
    }

    private fun onCommonSetup(event: FMLCommonSetupEvent) {
        val integrationHandler = CobbleworkersIntegrationHandler(NeoForgeIntegrationHelper)
        integrationHandler.addIntegrations()
    }

    private fun onBuildCreativeTabs(event: BuildCreativeModeTabContentsEvent) {
        if (event.tabKey == ItemGroups.FUNCTIONAL) {
            (event as ItemGroup.Entries).add(CobbleworkersBlocks.POKEBED_ITEM)
        }
    }

    private fun onRegisterPayloads(event: RegisterPayloadHandlersEvent) {
        val registrar = event.registrar("1.0.0")

        // Sanity Sync (Server -> Client)
        registrar.playToClient(
            SanitySyncPayload.ID,
            SanitySyncPayload.CODEC
        ) { payload: SanitySyncPayload, context: IPayloadContext ->
            context.enqueueWork {
                SanityHudClientState.update(payload.entries)
            }
        }
    }
}

@EventBusSubscriber(bus = EventBusSubscriber.Bus.GAME)
object SanityTickHandler {

    @SubscribeEvent
    fun onServerTick(event: LevelTickEvent.Post) {
        val world = event.level
        if (world is ServerWorld && !world.isClient) {
            SanitySyncTicker.tick(world)
        }
    }

    @SubscribeEvent
    fun onRegisterCommands(event: RegisterCommandsEvent) {
        CobbleworkersCommands.register(event.dispatcher)
    }
}