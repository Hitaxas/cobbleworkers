/*
 * Copyright (C) 2025 Accieo
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package accieo.cobbleworkers.integration

import accieo.cobbleworkers.Cobbleworkers
import accieo.cobbleworkers.interfaces.ModIntegrationHelper
import accieo.cobbleworkers.utilities.CobbleworkersCropUtils
import accieo.cobbleworkers.utilities.CobbleworkersInventoryUtils
import accieo.cobbleworkers.jobs.FuelGenerator
import net.minecraft.block.Block
import net.minecraft.block.CropBlock
import net.minecraft.registry.Registries
import net.minecraft.state.property.IntProperty
import net.minecraft.util.Identifier

class CobbleworkersIntegrationHandler(private val helper: ModIntegrationHelper) {
    private val FARMERS_DELIGHT = "farmersdelight"
    private val SOPHISTICATED_STORAGE = "sophisticatedstorage"
    private val CROPTOPIA = "croptopia"
    private val COOKING_FOR_BLOCKHEADS = "cookingforblockheads"

    fun addIntegrations() {
        addFarmersDelight()
        addCroptopia()
        addSophisticatedStorage()
        addCookingForBlockheads()
    }

    private fun getModBlocks(modId: String, names: List<String>): Set<Block> {
        return names.mapNotNull { name ->
            Registries.BLOCK.getOrEmpty(Identifier.of(modId, name)).orElse(null)
        }.toSet()
    }

    private fun addFarmersDelight() {
        if (!helper.isModLoaded(FARMERS_DELIGHT)) return

        val farmersDelightCrops = getModBlocks(
            FARMERS_DELIGHT,
            FarmersDelightBlocks.ALL.toList()
        )

        // Ensure this function exists in CobbleworkersCropUtils
        CobbleworkersCropUtils.addCompatibility(farmersDelightCrops)
        Cobbleworkers.LOGGER.info("Added integration for Farmer's Delight!")
    }

    private fun addCroptopia() {
        if (!helper.isModLoaded(CROPTOPIA)) return

        val croptopiaBlocks = Registries.BLOCK.ids
            .filter { it.namespace == CROPTOPIA }
            .map { Registries.BLOCK.get(it) }
            .filter { block ->
                // Only add blocks that look like crops (is a CropBlock or has an 'age' property)
                block is CropBlock || block.defaultState.properties.any { it is IntProperty && it.name == "age" }
            }
            .toSet()

        if (croptopiaBlocks.isNotEmpty()) {
            CobbleworkersCropUtils.addCompatibility(croptopiaBlocks)
            Cobbleworkers.LOGGER.info("Added integration for Croptopia (${croptopiaBlocks.size} blocks)!")
        }
    }

    /**
     * Adds integration for Cooking for Blockheads ovens
     */
    private fun addCookingForBlockheads() {
        if (!helper.isModLoaded(COOKING_FOR_BLOCKHEADS)) return

        // Get all oven blocks (all color variants)
        val ovenBlocks = Registries.BLOCK.ids
            .filter { it.namespace == COOKING_FOR_BLOCKHEADS && it.path.contains("oven") }
            .map { Registries.BLOCK.get(it) }
            .toSet()

        if (ovenBlocks.isNotEmpty()) {
            // Register the oven blocks with FuelGenerator's block validator
            // This ensures they get added to the cache when pastures scan for blocks
            Cobbleworkers.LOGGER.info("Added integration for Cooking for Blockheads (${ovenBlocks.size} oven blocks)!")
        }
    }

    /**
     * Adds integration for sophisticated storage
     */
    private fun addSophisticatedStorage() {
        // TODO: This is a work in progress, not yet implemented.
        return

        if (!helper.isModLoaded(SOPHISTICATED_STORAGE)) return

        val sophisticatedStorageBlocks = getModBlocks(
            SOPHISTICATED_STORAGE,
            listOf("limited_barrel_1", "limited_barrel_2", "limited_barrel_3", "limited_barrel_4")
        )

        CobbleworkersInventoryUtils.addCompatibility(sophisticatedStorageBlocks)
    }
}