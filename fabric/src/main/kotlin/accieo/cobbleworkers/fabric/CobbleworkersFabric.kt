/*
 * Copyright (C) 2025 Accieo
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package accieo.cobbleworkers.fabric

import accieo.cobbleworkers.Cobbleworkers
import accieo.cobbleworkers.commands.CobbleworkersCommands
import accieo.cobbleworkers.registry.CobbleworkersBlocks
import accieo.cobbleworkers.fabric.integration.FabricIntegrationHelper
import accieo.cobbleworkers.integration.CobbleworkersIntegrationHandler
import accieo.cobbleworkers.sanity.SanityPlatformNetworking
import accieo.cobbleworkers.sanity.SanityPlatformNetworkingInstance
import accieo.cobbleworkers.network.fabric.CobbleworkersFabricPackets
import accieo.cobbleworkers.network.fabric.FabricSanityNetworking
import accieo.cobbleworkers.network.fabric.FabricWorkToggleNetworking
import accieo.cobbleworkers.party.PartyWorkerFabric
import accieo.cobbleworkers.sanity.SanityEntry
import accieo.cobbleworkers.sanity.SanitySyncPayload

import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents
import net.minecraft.item.ItemGroups

import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking

import net.minecraft.server.network.ServerPlayerEntity

/**
 * Fabric entrypoint.
 */
object CobbleworkersFabric : ModInitializer {
    override fun onInitialize() {

        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            CobbleworkersCommands.register(dispatcher)
        }

        // Register platform networking
        SanityPlatformNetworkingInstance = FabricSanityNetworking

        Cobbleworkers.init()
        CobbleworkersFabricPackets.registerCommon()
        FabricSanityNetworking.registerServerHandlers()
        FabricWorkToggleNetworking.register()
        PartyWorkerFabric.init()
        CobbleworkersBlocks.register()

        ItemGroupEvents.modifyEntriesEvent(ItemGroups.FUNCTIONAL).register { content ->
            content.add(CobbleworkersBlocks.POKEBED_ITEM)
        }

        ServerLifecycleEvents.SERVER_STARTING.register { _ ->
            val integrationHandler = CobbleworkersIntegrationHandler(FabricIntegrationHelper)
            integrationHandler.addIntegrations()
        }

    }
}

/**
 * Fabric networking backend
 */
object FabricSanityNetworking : SanityPlatformNetworking {
    override fun sendSanityUpdate(player: ServerPlayerEntity, list: List<SanityEntry>) {
        ServerPlayNetworking.send(player, SanitySyncPayload(list))
    }
}
