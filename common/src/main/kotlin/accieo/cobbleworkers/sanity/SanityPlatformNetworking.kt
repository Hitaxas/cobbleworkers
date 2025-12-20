/*
 * Copyright (C) 2025 HitaxasTV
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package accieo.cobbleworkers.sanity

import net.minecraft.server.network.ServerPlayerEntity

interface SanityPlatformNetworking {
    fun sendSanityUpdate(player: ServerPlayerEntity, list: List<SanityEntry>)
}

lateinit var SanityPlatformNetworkingInstance: SanityPlatformNetworking
