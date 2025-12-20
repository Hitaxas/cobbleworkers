/*
 * Copyright (C) 2025 HitaxasTV
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package accieo.cobbleworkers.sanity

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.text.Text
import net.minecraft.util.Formatting

object SanityHudRenderer {

    fun render(context: DrawContext) {
        // Update every frame
        SanityHudClientState.tick()

        val mc = MinecraftClient.getInstance()
        val list: List<SanityEntry> = SanityHudClientState.sanityList

        if (list.isEmpty()) return

        val matrices: MatrixStack = context.matrices

        val startX = mc.window.scaledWidth - 140
        var y = 40

        for (entry in list.sortedBy { it.sanity }.take(6)) {

            val displaySanity = SanityHudClientState.getDisplaySanity(entry.uuid)

            val color = when {
                displaySanity >= 70 -> Formatting.GREEN
                displaySanity >= 50 -> Formatting.YELLOW
                else -> Formatting.RED
            }

            val text = Text.literal("${entry.name} ${displaySanity}%").formatted(color)

            // TEXT
            context.drawTextWithShadow(
                mc.textRenderer,
                text,
                startX,
                y,
                0xFFFFFF
            )

            // BAR BACKGROUND
            context.fill(
                startX,
                y + 10,
                startX + 100,
                y + 18,
                0xAA000000.toInt()
            )

            val width = ((displaySanity / 100f) * 100f).toInt()

            context.fill(
                startX,
                y + 10,
                startX + width,
                y + 18,
                0xAAFFFFFF.toInt()
            )

            y += 22
        }
    }
}