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
import accieo.cobbleworkers.utilities.CobbleworkersNavigationUtils
import accieo.cobbleworkers.utilities.CobbleworkersTypeUtils
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import net.minecraft.block.Block
import net.minecraft.block.FarmlandBlock
import net.minecraft.util.math.BlockPos
import net.minecraft.world.World

/**
 * A worker job for a Pokémon to find, navigate to, and irrigate dry farmland.
 * Improved to target any available dry farmland in range to prevent "clustering."
 */
object CropIrrigator : Worker {

    private val config = CobbleworkersConfigHolder.config.irrigation

    override val jobType: JobType = JobType.CropIrrigator

    /**
     * Valid farmland = any block with a MOISTURE property
     */
    override val blockValidator: ((World, BlockPos) -> Boolean) = { world, pos ->
        world.getBlockState(pos).contains(FarmlandBlock.MOISTURE)
    }

    override fun shouldRun(pokemonEntity: PokemonEntity): Boolean {
        if (!config.cropIrrigatorsEnabled) return false

        return CobbleworkersTypeUtils.isAllowedByType(
            config.typeIrrigatesCrops,
            pokemonEntity
        ) || isDesignatedIrrigator(pokemonEntity)
    }

    override fun tick(world: World, origin: BlockPos, pokemonEntity: PokemonEntity) {
        val pokemonId = pokemonEntity.pokemon.uuid
        val currentTarget = CobbleworkersNavigationUtils.getTarget(pokemonId, world)

        // Find a new target if we don't have one or if current target is already hydrated
        if (currentTarget == null || isHydrated(world, currentTarget)) {
            if (currentTarget != null) CobbleworkersNavigationUtils.releaseTarget(pokemonId, world)

            val newTarget = findAvailableDryFarmland(world, origin)
            if (newTarget != null) {
                CobbleworkersNavigationUtils.claimTarget(pokemonId, newTarget, world)
            }
            return
        }

        // Navigate to the specific dry block
        if (!CobbleworkersNavigationUtils.isPokemonAtPosition(pokemonEntity, currentTarget, 1.5)) {
            CobbleworkersNavigationUtils.navigateTo(pokemonEntity, currentTarget)
            return
        }

        // Irrigate the target and surrounding blocks in a burst
        irrigateFarmland(
            world,
            currentTarget,
            config.irrigationRadius.coerceAtLeast(2)
        )

        // Finish task and release target
        CobbleworkersNavigationUtils.releaseTarget(pokemonId, world)
    }

    /**
     * Scans for dry farmland within the worker's radius that isn't already targeted.
     */
    private fun findAvailableDryFarmland(world: World, origin: BlockPos): BlockPos? {
        val radius = 16 // Horizontal search range
        val dryBlocks = mutableListOf<BlockPos>()

        // Scan area for blocks that need water
        BlockPos.iterate(
            origin.add(-radius, -2, -radius),
            origin.add(radius, 2, radius)
        ).forEach { pos ->
            val state = world.getBlockState(pos)
            if (state.contains(FarmlandBlock.MOISTURE)) {
                if (state.get(FarmlandBlock.MOISTURE) < FarmlandBlock.MAX_MOISTURE) {
                    // Only consider it if no one else is heading there
                    if (!CobbleworkersNavigationUtils.isTargeted(pos, world) &&
                        !CobbleworkersNavigationUtils.isRecentlyExpired(pos, world)
                    ) {
                        dryBlocks.add(pos.toImmutable())
                    }
                }
            }
        }

        // Return the closest dry block from the list of available ones
        return dryBlocks.minByOrNull { it.getSquaredDistance(origin) }
    }

    /**
     * Helper to check if a specific block is already fully moisturized
     */
    private fun isHydrated(world: World, pos: BlockPos): Boolean {
        val state = world.getBlockState(pos)
        return if (state.contains(FarmlandBlock.MOISTURE)) {
            state.get(FarmlandBlock.MOISTURE) >= FarmlandBlock.MAX_MOISTURE
        } else true
    }

    /**
     * Irrigates all farmland-like blocks in radius
     */
    private fun irrigateFarmland(world: World, blockPos: BlockPos, radius: Int) {
        BlockPos.iterate(
            blockPos.add(-radius, 0, -radius),
            blockPos.add(radius, 0, radius)
        ).forEach { pos ->
            val state = world.getBlockState(pos)

            if (state.contains(FarmlandBlock.MOISTURE)) {
                val moisture = state.get(FarmlandBlock.MOISTURE)
                if (moisture < FarmlandBlock.MAX_MOISTURE) {
                    world.setBlockState(
                        pos,
                        state.with(FarmlandBlock.MOISTURE, FarmlandBlock.MAX_MOISTURE),
                        Block.NOTIFY_LISTENERS
                    )
                }
            }
        }
    }

    private fun isDesignatedIrrigator(pokemonEntity: PokemonEntity): Boolean {
        val speciesName = pokemonEntity.pokemon.species.translatedName.string.lowercase()
        return config.cropIrrigators.any { it.lowercase() == speciesName }
    }


    override fun isActivelyWorking(pokemonEntity: PokemonEntity): Boolean {
        val uuid = pokemonEntity.pokemon.uuid
        val world = pokemonEntity.world

        // If currently assigned a farmland target → working
        val target = CobbleworkersNavigationUtils.getTarget(uuid, world)
        if (target != null) return true

        // If refusing / on break → NOT working
        if (accieo.cobbleworkers.sanity.SanityManager.isRefusingWork(pokemonEntity))
            return false

        // If sleeping → NOT working
        if (accieo.cobbleworkers.sanity.SanityManager.isSleepingDuringBreak(pokemonEntity))
            return false

        // Otherwise:
        // Pokémon is in "duty mode", allowed to work, can be scanning or roaming
        // Count as working so sanity drains
        return true
    }

  
}