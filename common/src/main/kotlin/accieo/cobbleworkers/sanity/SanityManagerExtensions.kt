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

    fun getSanity(pokemon: Pokemon): Double {
        val tag = pokemon.persistentData
        return if (tag.contains("cobbleworkers_sanity")) {
            tag.getDouble("cobbleworkers_sanity").coerceIn(0.0, SanityManager.MAX_SANITY)
        } else {
            SanityManager.MAX_SANITY
        }
    }

    fun getSanityPercent(pokemon: Pokemon): Int {
        val tag = pokemon.persistentData
        return if (tag.contains("cobbleworkers_sanity")) {
            tag.getDouble("cobbleworkers_sanity").toInt().coerceIn(0, 100)
        } else {
            100
        }
    }

    fun getStatus(pokemon: Pokemon): String {
        val currentSanity = getSanity(pokemon)

        return when {
            currentSanity < SanityManager.REFUSE_THRESHOLD -> "Has just about had it... (${currentSanity.toInt()}%)"
            currentSanity < SanityManager.COMPLAINING_THRESHOLD -> "Unhappy with work conditions. (${currentSanity.toInt()}%)"
            currentSanity >= 80 -> "Is hard at work. (${currentSanity.toInt()}%)"
            currentSanity >= 60 -> "Is in good condition. (${currentSanity.toInt()}%)"
            else -> "Is getting a bit tired... (${currentSanity.toInt()}%)"
        }
    }

    fun isComplaining(pokemon: Pokemon): Boolean {
        val currentSanity = getSanity(pokemon)
        return currentSanity < SanityManager.COMPLAINING_THRESHOLD && 
               currentSanity >= SanityManager.REFUSE_THRESHOLD
    }

    fun needsForcedBreak(pokemon: Pokemon): Boolean {
        return getSanity(pokemon) < SanityManager.REFUSE_THRESHOLD
    }
}