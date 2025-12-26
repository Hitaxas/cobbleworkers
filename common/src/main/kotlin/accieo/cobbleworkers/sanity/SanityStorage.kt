/*
 * Copyright (C) 2025 HitaxasTV
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package accieo.cobbleworkers.sanity

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import net.minecraft.nbt.NbtCompound

object SanityStorage {

    private const val KEY = "cobbleworkers_sanity"

    fun load(pokemon: PokemonEntity): Int {
        val data: NbtCompound = pokemon.pokemon.persistentData

        if (!data.contains(KEY)) {
            data.putInt(KEY, 100) // default full sanity for new Pokémon
            return 100
        }

        return data.getInt(KEY).coerceIn(0, 100)
    }

    fun save(pokemon: PokemonEntity, sanity: Int) {
        pokemon.pokemon.persistentData.putInt(KEY, sanity.coerceIn(0, 100))
    }
}
