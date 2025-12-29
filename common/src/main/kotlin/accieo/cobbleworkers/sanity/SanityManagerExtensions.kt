/*
 * Copyright (C) 2025 HitaxasTV
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package accieo.cobbleworkers.sanity

import com.cobblemon.mod.common.pokemon.Pokemon

object SanityManagerExtensions {

    fun getSanityPercent(pokemon: Pokemon): Int {
        val tag = pokemon.persistentData
        return if (tag.contains("cobbleworkers_sanity")) {
            tag.getDouble("cobbleworkers_sanity").toInt().coerceIn(0, 100)
        } else {
            100
        }
    }
}