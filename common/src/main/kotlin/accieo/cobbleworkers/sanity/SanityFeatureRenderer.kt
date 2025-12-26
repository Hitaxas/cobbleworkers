/*
 * Copyright (C) 2025 HitaxasTV
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package accieo.cobbleworkers.sanity

import com.cobblemon.mod.common.api.gui.blitk
import com.cobblemon.mod.common.client.CobblemonResources
import com.cobblemon.mod.common.client.gui.summary.featurerenderers.BarSummarySpeciesFeatureRenderer
import com.cobblemon.mod.common.pokemon.Pokemon
import com.cobblemon.mod.common.util.cobblemonResource
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Text

/**
 * Feature renderer that displays Pokemon sanity on the stats/other screen.
 */
class SanityFeatureRenderer(
    pokemon: Pokemon
) : BarSummarySpeciesFeatureRenderer(
    "sanity",
    Text.literal("Sanity"),
    cobblemonResource("textures/gui/summary/summary_stats_other_bar.png"),
    cobblemonResource("textures/gui/summary/summary_stats_friendship_overlay.png"),
    pokemon,
    0,
    100,
    SanityManagerExtensions.getSanityPercent(pokemon)
) {

    override fun render(context: DrawContext, x: Float, y: Float, pokemon: Pokemon): Boolean {
        renderElement(context, x, y, pokemon)
        return true
    }

    override fun renderBar(context: DrawContext, x: Float, y: Float, barValue: Int, barRatio: Float, barWidth: Int) {
        val currentSanity = barValue

        val (red, green, blue) = when {
            currentSanity >= 70 -> Triple(0.27, 1.0, 0.47)    // Green
            currentSanity >= 50 -> Triple(1.0, 0.87, 0.2)     // Yellow
            currentSanity >= 30 -> Triple(1.0, 0.53, 0.2)     // Orange
            else -> Triple(1.0, 0.27, 0.27)                   // Red
        }

        val barStartOffset = 3

        val trackWidth = 84

        val fillWidth = (trackWidth.toDouble() * (currentSanity.toDouble() / 100.0)).toInt()

        blitk(
            matrixStack = context.matrices,
            texture = CobblemonResources.WHITE,
            x = x + barStartOffset,
            y = y + 13,
            height = 10,
            width = fillWidth,
            red = red,
            green = green,
            blue = blue
        )
    }
}