/*
 * Copyright (C) 2025 HitaxasTV
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package accieo.cobbleworkers.sanity

import com.cobblemon.mod.common.client.gui.summary.Summary
import com.cobblemon.mod.common.client.gui.summary.widgets.screens.stats.StatWidget
import net.minecraft.client.gui.DrawContext

object SanityFeatureRegistration {

    private const val BAR_OFFSET_Y = 29
    private const val BARS_PER_PAGE = 4

    fun register() {
        println("[CobbleWorkers] Sanity feature registration initialized")
    }

    fun renderOnSummary(context: DrawContext, screen: Any) {
        if (screen !is Summary) return

        try {
            // Find the StatWidget
            val children = screen.children()
            val statWidget = children.filterIsInstance<StatWidget>().firstOrNull() ?: return

            val hasRideBehavior = statWidget.pokemon.form.riding.behaviours != null
            val otherTabIndex = if (hasRideBehavior) 4 else 3

            // Only render on the "Other" tab
            if (statWidget.statTabIndex != otherTabIndex) return

            val pokemon = statWidget.pokemon

            val liveSanity = SanityHudClientState.getDisplaySanity(pokemon.uuid)

            if (liveSanity > 0.0) {
                pokemon.persistentData.putDouble("cobbleworkers_sanity", liveSanity)
            }

            val sanityRenderer = SanityFeatureRenderer(pokemon)

            val barPosX = statWidget.x + 9F
            val baseY = statWidget.y + 15F

            val sanityIndex = 3
            val currentPage = statWidget.otherStatsPageIndex

            val sanityPage = sanityIndex / BARS_PER_PAGE

            if (currentPage == sanityPage) {
                val positionOnPage = sanityIndex % BARS_PER_PAGE
                val barPosY = baseY + (BAR_OFFSET_Y * positionOnPage)

                sanityRenderer.render(context, barPosX, barPosY, pokemon)
            }

        } catch (e: Exception) {
            println("[CobbleWorkers] Error rendering sanity: ${e.message}")
            e.printStackTrace()
        }
    }
}