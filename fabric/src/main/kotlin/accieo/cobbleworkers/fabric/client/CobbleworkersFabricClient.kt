/*
 * Copyright (C) 2025 Accieo
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package accieo.cobbleworkers.fabric.client

import accieo.cobbleworkers.sanity.SanityFeatureRegistration
import accieo.cobbleworkers.sanity.SanityHudRenderer
import accieo.cobbleworkers.utilities.CobbleworkersWorkToggle
import com.cobblemon.mod.common.api.events.CobblemonEvents
import com.cobblemon.mod.common.client.gui.interact.wheel.InteractWheelOption
import com.cobblemon.mod.common.util.cobblemonResource
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import accieo.cobbleworkers.network.fabric.FabricSanityNetworking
import accieo.cobbleworkers.network.payloads.ToggleWorkPayload
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.MinecraftClient

object CobbleworkersFabricClient : ClientModInitializer {
    override fun onInitializeClient() {
        FabricSanityNetworking.registerClientHandlers()

        // Initialize HUD renderer for the workers panel
        HudRenderCallback.EVENT.register { context, tickDelta ->
            SanityHudRenderer.render(context)
        }

        // Register sanity feature renderer
        SanityFeatureRegistration.register()

        // Hook into screen rendering to draw sanity on Summary screens
        ScreenEvents.AFTER_INIT.register { client, screen, scaledWidth, scaledHeight ->
            ScreenEvents.afterRender(screen).register { _, context, mouseX, mouseY, delta ->
                SanityFeatureRegistration.renderOnSummary(context, screen)
            }
        }

        // Register work toggle interact wheel option
        registerWorkToggleInteraction()
    }

    private fun registerWorkToggleInteraction() {
        CobblemonEvents.POKEMON_INTERACTION_GUI_CREATION.subscribe { event ->
            val client = MinecraftClient.getInstance()
            val world = client.world ?: return@subscribe

            val entity = world.entities
                .firstOrNull { it.uuid == event.pokemonID }

            if (entity is com.cobblemon.mod.common.entity.pokemon.PokemonEntity) {
                val canWork = CobbleworkersWorkToggle.canWork(entity.pokemon)

                val workToggle = InteractWheelOption(
                    iconResource = if (canWork) {
                        cobblemonResource("textures/gui/interact/interact_wheel_icon_cancel.png")
                    } else {
                        cobblemonResource("textures/gui/interact/interact_wheel_icon_check.png")
                    },
                    tooltipText = if (canWork) {
                        "cobbleworkers.ui.interact.disable_work"
                    } else {
                        "cobbleworkers.ui.interact.enable_work"
                    },
                    enabled = true,
                    onPress = {
                        ClientPlayNetworking.send(
                            ToggleWorkPayload(entity.uuid)
                        )
                        client.setScreen(null)
                    }
                )

                event.addFillingOption(workToggle)
            }
        }
    }

}