/*
 * Copyright (C) 2025 HitaxasTV
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package accieo.cobbleworkers.config

import me.shedaniel.autoconfig.annotation.ConfigEntry

/**
 * Configuration for Pokémon mechanics modifications.
 */
class PokemonMechanicsGroup {

    @ConfigEntry.Gui.Tooltip
    var syncPokemonHealth: Boolean = true

    @ConfigEntry.Gui.Tooltip
    var enhancedCatchRates: Boolean = true

    @ConfigEntry.Gui.Tooltip
    var syncInterval: Int = 20 // Ticks between syncs (20 = 1 second)
}