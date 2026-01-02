/*
 * Copyright (C) 2026 HitaxasTV
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package accieo.cobbleworkers.commands

import accieo.cobbleworkers.utilities.CobbleworkersWorkToggle
import com.cobblemon.mod.common.Cobblemon
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import net.minecraft.server.command.CommandManager.argument
import net.minecraft.server.command.CommandManager.literal
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.text.Text

object CobbleworkersCommands {
    fun register(dispatcher: CommandDispatcher<ServerCommandSource>) {
        dispatcher.register(
            literal("cobbleworkers").then(
                literal("work")
                    .then(literal("on")
                        .executes { setTeamWork(it.source, true) }
                        .then(argument("slot", IntegerArgumentType.integer(1, 6)).executes {
                            setSlotWork(it.source, true, IntegerArgumentType.getInteger(it, "slot"))
                        })
                    )
                    .then(literal("off")
                        .executes { setTeamWork(it.source, false) }
                        .then(argument("slot", IntegerArgumentType.integer(1, 6)).executes {
                            setSlotWork(it.source, false, IntegerArgumentType.getInteger(it, "slot"))
                        })
                    )
                    .then(literal("status").executes { showStatus(it.source) })
            )
        )
    }

    private fun setTeamWork(source: ServerCommandSource, canWork: Boolean): Int {
        val player = source.player ?: return 0
        val party = Cobblemon.storage.getParty(player)

        party.forEach { pokemon ->
            CobbleworkersWorkToggle.setCanWork(pokemon, canWork)
        }

        val status = if (canWork) "§aenabled" else "§cdisabled"
        source.sendFeedback({ Text.literal("Work $status for your entire party!") }, false)
        return 1
    }

    private fun setSlotWork(source: ServerCommandSource, canWork: Boolean, slot: Int): Int {
        val player = source.player ?: return 0
        val party = Cobblemon.storage.getParty(player)
        val pokemon = party.get(slot - 1)

        if (pokemon == null) {
            source.sendError(Text.literal("No Pokémon found in slot $slot!"))
            return 0
        }

        CobbleworkersWorkToggle.setCanWork(pokemon, canWork)
        val status = if (canWork) "§aenabled" else "§cdisabled"
        val name = pokemon.getDisplayName().string

        source.sendFeedback({
            Text.literal("Work $status for $name (Slot $slot)!")
        }, false)
        return 1
    }

    private fun showStatus(source: ServerCommandSource): Int {
        val player = source.player ?: return 0
        val party = Cobblemon.storage.getParty(player)

        source.sendFeedback({ Text.literal("§6--- Cobbleworkers Party Status ---") }, false)

        for (i in 0..5) {
            val pokemon = party.get(i)
            val slotNum = i + 1
            if (pokemon != null) {
                val canWork = CobbleworkersWorkToggle.canWork(pokemon)
                val statusText = if (canWork) "§a[ENABLED]" else "§c[DISABLED]"
                val name = pokemon.getDisplayName().string

                source.sendFeedback({
                    Text.literal("§7Slot $slotNum: §f$name $statusText")
                }, false)
            } else {
                source.sendFeedback({ Text.literal("§7Slot $slotNum: §8Empty") }, false)
            }
        }
        return 1
    }
}