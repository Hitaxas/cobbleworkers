/*
 * Copyright (C) 2025 Accieo
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package accieo.cobbleworkers.jobs

import accieo.cobbleworkers.config.CobbleworkersConfigHolder
import accieo.cobbleworkers.enums.JobType
import accieo.cobbleworkers.interfaces.Worker
import accieo.cobbleworkers.utilities.CobbleworkersCropUtils
import accieo.cobbleworkers.utilities.CobbleworkersInventoryUtils
import accieo.cobbleworkers.utilities.CobbleworkersNavigationUtils
import accieo.cobbleworkers.utilities.CobbleworkersTypeUtils
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import net.minecraft.item.ItemStack
import net.minecraft.util.math.BlockPos
import net.minecraft.world.World
import java.util.UUID
import kotlin.text.lowercase

/**
 * A worker job for a Pokémon to find, navigate to, and harvest fully grown crops.
 * Harvested items are deposited into the nearest available inventory.
 */
object CropHarvester : Worker {
    private val heldItemsByPokemon = mutableMapOf<UUID, List<ItemStack>>()
    private val failedDepositLocations = mutableMapOf<UUID, MutableSet<BlockPos>>()
    private val pokemonBreakingBlocks = mutableMapOf<UUID, BlockPos>()
    private val config = CobbleworkersConfigHolder.config.cropHarvest

    override val jobType: JobType = JobType.CropHarvester
    override val blockValidator: ((World, BlockPos) -> Boolean) = { world: World, pos: BlockPos ->
        val state = world.getBlockState(pos)
        state.block in CobbleworkersCropUtils.validCropBlocks
    }

    override fun shouldRun(pokemonEntity: PokemonEntity): Boolean {
        if (!config.cropHarvestersEnabled) return false
        return CobbleworkersTypeUtils.isAllowedByType(config.typeHarvestsCrops, pokemonEntity) || isDesignatedHarvester(pokemonEntity)
    }

    override fun tick(world: World, origin: BlockPos, pokemonEntity: PokemonEntity) {
        val pokemonId = pokemonEntity.pokemon.uuid
        val heldItems = heldItemsByPokemon[pokemonId]

        if (heldItems.isNullOrEmpty()) {
            failedDepositLocations.remove(pokemonId)
            handleHarvesting(world, origin, pokemonEntity)
        } else {
            val breakingPos = pokemonBreakingBlocks.remove(pokemonId)
            if (breakingPos != null) {
                CobbleworkersCropUtils.cancelBreaking(breakingPos, world)
            }
            CobbleworkersInventoryUtils.handleDepositing(world, origin, pokemonEntity, heldItems, failedDepositLocations, heldItemsByPokemon)
        }
    }

    private fun handleHarvesting(world: World, origin: BlockPos, pokemonEntity: PokemonEntity) {
        val pokemonId = pokemonEntity.pokemon.uuid
        val breakingPos = pokemonBreakingBlocks[pokemonId]

        if (breakingPos != null) {
            val blockState = world.getBlockState(breakingPos)
            if (!CobbleworkersCropUtils.isHarvestable(blockState) || !world.getBlockState(breakingPos).isOf(blockState.block)) {
                pokemonBreakingBlocks.remove(pokemonId)
                CobbleworkersCropUtils.cancelBreaking(breakingPos, world)
                CobbleworkersNavigationUtils.releaseTarget(pokemonId, world)
                return
            }

            if (!CobbleworkersNavigationUtils.isPokemonAtPosition(pokemonEntity, breakingPos)) {
                CobbleworkersNavigationUtils.navigateTo(pokemonEntity, breakingPos)
                return
            }

            CobbleworkersCropUtils.harvestCrop(world, breakingPos, pokemonEntity, heldItemsByPokemon, config)

            if (heldItemsByPokemon.containsKey(pokemonId)) {
                pokemonBreakingBlocks.remove(pokemonId)
                CobbleworkersNavigationUtils.releaseTarget(pokemonId, world)
            }
            return
        }

        val currentTarget = CobbleworkersNavigationUtils.getTarget(pokemonId, world)

        if (currentTarget == null) {

            val availableCrop = CobbleworkersCropUtils.findAvailableCrop(world, origin)

            if (availableCrop != null) {
                CobbleworkersNavigationUtils.claimTarget(pokemonId, availableCrop, world)
            }
            return
        }

        if (CobbleworkersNavigationUtils.isPokemonAtPosition(pokemonEntity, currentTarget)) {
            val blockState = world.getBlockState(currentTarget)

            if (!CobbleworkersCropUtils.isHarvestable(blockState)) {
                CobbleworkersNavigationUtils.releaseTarget(pokemonId, world)
                return
            }

            if (CobbleworkersCropUtils.requiresBreaking(blockState.block)) {
                pokemonBreakingBlocks[pokemonId] = currentTarget
            }

            CobbleworkersCropUtils.harvestCrop(world, currentTarget, pokemonEntity, heldItemsByPokemon, config)

            if (!CobbleworkersCropUtils.requiresBreaking(blockState.block) && heldItemsByPokemon.containsKey(pokemonId)) {
                CobbleworkersNavigationUtils.releaseTarget(pokemonId, world)
            }
        } else {
            CobbleworkersNavigationUtils.navigateTo(pokemonEntity, currentTarget)
        }
    }

    /**
     * Checks if the Pokémon qualifies as a harvester because its species is
     * explicitly listed in the config.
     */
    private fun isDesignatedHarvester(pokemonEntity: PokemonEntity): Boolean {
        val speciesName = pokemonEntity.pokemon.species.translatedName.string.lowercase()
        return config.cropHarvesters.any { it.lowercase() == speciesName }
    }
}