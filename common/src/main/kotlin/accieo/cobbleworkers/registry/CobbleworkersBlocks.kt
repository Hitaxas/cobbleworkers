/*
 * Copyright (C) 2025 HitaxasTV
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package accieo.cobbleworkers.registry

import accieo.cobbleworkers.blocks.PokeBedBlock
import net.minecraft.block.AbstractBlock
import net.minecraft.block.Block
import net.minecraft.item.BlockItem
import net.minecraft.item.Item
import net.minecraft.registry.Registries
import net.minecraft.registry.Registry
import net.minecraft.sound.BlockSoundGroup
import net.minecraft.util.Identifier

/**
 * Registry for CobbleWorkers blocks and items.
 */
object CobbleworkersBlocks {

    // Block instance
    val POKEBED: Block = PokeBedBlock(
        AbstractBlock.Settings.create()
            .strength(2.0f, 3.0f)
            .sounds(BlockSoundGroup.WOOD)
            .nonOpaque()
    )

    // Block item
    val POKEBED_ITEM: Item = BlockItem(
        POKEBED,
        Item.Settings()
    )

    /**
     * Register all blocks and items.
     * Call this from your mod initializer.
     */
    fun register() {
        // Register block
        Registry.register(
            Registries.BLOCK,
            Identifier("cobbleworkers", "pokebed"),
            POKEBED
        )

        // Register item
        Registry.register(
            Registries.ITEM,
            Identifier("cobbleworkers", "pokebed"),
            POKEBED_ITEM
        )

        println("[CobbleWorkers] Registered PokeBed block and item")
    }
}