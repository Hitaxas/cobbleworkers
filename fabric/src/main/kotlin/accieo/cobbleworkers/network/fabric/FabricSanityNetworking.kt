/*
 * Copyright (C) 2026 HitaxasTV
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package accieo.cobbleworkers.network.fabric

import accieo.cobbleworkers.sanity.SanityEntry
import accieo.cobbleworkers.sanity.SanityHudClientState
import accieo.cobbleworkers.sanity.SanityPlatformNetworking
import accieo.cobbleworkers.sanity.SanitySyncPayload
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.network.ServerPlayerEntity

object FabricSanityNetworking : SanityPlatformNetworking {

    fun registerServerHandlers() {
        ServerPlayNetworking.registerGlobalReceiver(
            SanitySyncPayload.ID
        ) { payload, context ->
            context.player()
            // handle payload
        }
    }

    fun registerClientHandlers() {
        ClientPlayNetworking.registerGlobalReceiver(
            SanitySyncPayload.ID
        ) { payload, context ->
            context.client().execute {
                SanityHudClientState.update(payload.entries)
            }
        }
    }

    override fun sendSanityUpdate(
        player: ServerPlayerEntity,
        list: List<SanityEntry>
    ) {
        ServerPlayNetworking.send(
            player,
            SanitySyncPayload(list)
        )
    }
}
