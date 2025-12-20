/*
 * Copyright (C) 2025 HitaxasTV
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package accieo.cobbleworkers.sanity

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import net.minecraft.network.PacketByteBuf
import net.minecraft.util.Identifier
import java.util.UUID

data class SanityEntry(
    val uuid: UUID,
    val name: String,
    val sanity: Int,
    val status: String
)

object SanitySyncPacket {

    val ID = Identifier("cobbleworkers", "sanity_sync")

    fun write(buf: PacketByteBuf, list: List<SanityEntry>) {
        buf.writeInt(list.size)
        list.forEach {
            buf.writeUuid(it.uuid)
            buf.writeString(it.name) // right now it is showing pokemon species name, and not nicknames...
            buf.writeInt(it.sanity)
            buf.writeString(it.status)
        }
    }

    fun read(buf: PacketByteBuf): List<SanityEntry> {
        val out = mutableListOf<SanityEntry>()
        repeat(buf.readInt()) {
            out += SanityEntry(
                buf.readUuid(),
                buf.readString(),
                buf.readInt(),
                buf.readString()
            )
        }
        return out
    }
}
