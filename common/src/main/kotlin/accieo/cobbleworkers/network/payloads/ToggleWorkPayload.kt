/*
 * Copyright (C) 2026 HitaxasTV
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package accieo.cobbleworkers.network.payloads

import net.minecraft.network.RegistryByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload
import net.minecraft.util.Identifier
import net.minecraft.util.Uuids
import java.util.UUID

data class ToggleWorkPayload(val pokemonId: UUID) : CustomPayload {

    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    companion object {
        val ID = CustomPayload.Id<ToggleWorkPayload>(
            Identifier("cobbleworkers", "toggle_work")
        )

        val CODEC: PacketCodec<RegistryByteBuf, ToggleWorkPayload> = PacketCodec.tuple(
            Uuids.PACKET_CODEC, ToggleWorkPayload::pokemonId,
            ::ToggleWorkPayload
        )
    }
}