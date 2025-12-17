/*
 * Copyright (C) 2025 Accieo
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package accieo.cobbleworkers.utilities

import accieo.cobbleworkers.cache.CobbleworkersCacheManager
import accieo.cobbleworkers.config.CobbleworkersConfig
import accieo.cobbleworkers.enums.JobType
import accieo.cobbleworkers.integration.FarmersDelightBlocks
import accieo.cobbleworkers.integration.CroptopiaBlocks
import com.cobblemon.mod.common.CobblemonBlocks
import com.cobblemon.mod.common.block.HeartyGrainsBlock
import com.cobblemon.mod.common.block.RevivalHerbBlock
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import net.minecraft.block.*
import net.minecraft.item.ItemStack
import net.minecraft.loot.context.LootContextParameterSet
import net.minecraft.loot.context.LootContextParameters
import net.minecraft.registry.Registries
import net.minecraft.server.world.ServerWorld
import net.minecraft.state.property.IntProperty
import net.minecraft.state.property.Properties
import net.minecraft.state.property.Properties.AGE_3
import net.minecraft.util.math.BlockPos
import net.minecraft.world.World
import java.util.UUID
import net.minecraft.block.enums.DoubleBlockHalf

/**
 * Utility functions for crop related stuff.
 * Integrated with Vanilla, Cobblemon, Farmer's Delight, and Croptopia.
 */
object CobbleworkersCropUtils {

    val validCropBlocks: MutableSet<Block> = mutableSetOf(
        Blocks.POTATOES,
        Blocks.BEETROOTS,
        Blocks.CARROTS,
        Blocks.WHEAT,
        Blocks.SWEET_BERRY_BUSH,
        Blocks.CAVE_VINES,
        Blocks.CAVE_VINES_PLANT,
        Blocks.PUMPKIN,
        Blocks.MELON,
        CobblemonBlocks.REVIVAL_HERB,
        CobblemonBlocks.MEDICINAL_LEEK,
        CobblemonBlocks.VIVICHOKE_SEEDS,
        CobblemonBlocks.HEARTY_GRAINS,
        CobblemonBlocks.GALARICA_NUT_BUSH
    )

    // Track breaking progress for pumpkins/melons: BlockPos -> (startTime, pokemonUUID)
    private val breakingBlocks = mutableMapOf<BlockPos, Pair<Long, UUID>>()
    private const val BREAK_TIME_TICKS = 40L // 2 seconds (20 ticks per second)

    fun addCompatibility(externalBlocks: Set<Block>) = validCropBlocks.addAll(externalBlocks)

    fun isCroptopia(block: Block): Boolean = Registries.BLOCK.getId(block).namespace == "croptopia"

    fun isHarvestable(state: BlockState): Boolean {
        val block = state.block
        return block in validCropBlocks || isCroptopia(block) || block is CropBlock
    }

    fun findAvailableCrop(world: World, origin: BlockPos): BlockPos? {
        val possibleTargets = CobbleworkersCacheManager.getTargets(origin, JobType.CropHarvester)
        if (possibleTargets.isEmpty()) return null

        return possibleTargets
            .filter { pos ->
                val state = world.getBlockState(pos)

                isHarvestable(state) &&
                        isMatureCrop(world, pos) &&
                        !CobbleworkersNavigationUtils.isRecentlyExpired(pos, world) &&
                        !CobbleworkersNavigationUtils.isTargeted(pos, world) &&
                        !breakingBlocks.containsKey(pos)
            }
            .minByOrNull { it.getSquaredDistance(origin) }
    }

    fun findClosestCrop(world: World, origin: BlockPos): BlockPos? = findAvailableCrop(world, origin)

    /**
     * Checks if a block requires breaking animation (pumpkins/melons).
     */
    fun requiresBreaking(block: Block): Boolean {
        return block == Blocks.PUMPKIN || block == Blocks.MELON
    }

    /**
     * Starts breaking a block and returns false if still breaking, true if complete.
     */
    fun breakBlock(world: World, blockPos: BlockPos, pokemonEntity: PokemonEntity): Boolean {
        val block = world.getBlockState(blockPos).block
        if (!requiresBreaking(block)) return true

        val currentTime = world.time
        val pokemonUUID = pokemonEntity.pokemon.uuid

        val breakData = breakingBlocks[blockPos]

        if (breakData != null && breakData.second != pokemonUUID) {
            return false
        }

        if (breakData == null) {
            // Start breaking
            breakingBlocks[blockPos] = Pair(currentTime, pokemonUUID)
            showBreakProgress(world, blockPos, 0)
            playBreakSound(world, blockPos, block)
            return false
        }

        val elapsedTicks = currentTime - breakData.first
        val progress = (elapsedTicks.toFloat() / BREAK_TIME_TICKS * 10).toInt().coerceIn(0, 10)

        // Play sound periodically (every 8 ticks = 0.4 seconds)
        if (elapsedTicks % 8L == 0L) {
            playBreakSound(world, blockPos, block)
        }

        // Update break progress animation
        if (progress < 10) {
            showBreakProgress(world, blockPos, progress)
            return false
        }

        // Breaking complete
        breakingBlocks.remove(blockPos)
        showBreakProgress(world, blockPos, -1) // Clear animation
        return true
    }

    /**
     * Cancels breaking progress for a block.
     */
    fun cancelBreaking(blockPos: BlockPos, world: World) {
        if (breakingBlocks.remove(blockPos) != null) {
            showBreakProgress(world, blockPos, -1)
        }
    }

    /**
     * Shows block breaking progress animation.
     */
    private fun showBreakProgress(world: World, pos: BlockPos, progress: Int) {
        if (world is ServerWorld) {
            val breakerId = pos.asLong().toInt()
            world.setBlockBreakingInfo(breakerId, pos, progress)
        }
    }

    /**
     * Plays the block breaking sound.
     */
    private fun playBreakSound(world: World, pos: BlockPos, block: Block) {
        if (world is ServerWorld) {
            val soundGroup = block.defaultState.soundGroup
            world.playSound(
                null,
                pos,
                soundGroup.hitSound,
                net.minecraft.sound.SoundCategory.BLOCKS,
                (soundGroup.volume + 1.0f) / 2.0f,
                soundGroup.pitch * 0.8f
            )
        }
    }

    fun harvestCrop(
        world: World,
        blockPos: BlockPos,
        pokemonEntity: PokemonEntity,
        pokemonHeldItems: MutableMap<UUID, List<ItemStack>>,
        config: CobbleworkersConfig.CropHarvestGroup
    ) {
        val blockState = world.getBlockState(blockPos)
        val block = blockState.block

        if (!isHarvestable(blockState)) return

        if (blockState.contains(Properties.DOUBLE_BLOCK_HALF) &&
            blockState.get(Properties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
            return
        }

        // For pumpkins/melons, check if breaking is complete
        if (requiresBreaking(block)) {
            if (!breakBlock(world, blockPos, pokemonEntity)) {
                return // Still breaking, don't harvest yet
            }
        }

        val lootParams = LootContextParameterSet.Builder(world as ServerWorld)
            .add(LootContextParameters.ORIGIN, blockPos.toCenterPos())
            .add(LootContextParameters.BLOCK_STATE, blockState)
            .add(LootContextParameters.TOOL, ItemStack.EMPTY)
            .addOptional(LootContextParameters.THIS_ENTITY, pokemonEntity)

        val drops = blockState.getDroppedStacks(lootParams)
        if (drops.isNotEmpty()) {
            pokemonHeldItems[pokemonEntity.pokemon.uuid] = drops
        }

        val ageProp = getAgeProperty(blockState)
        val id = Registries.BLOCK.getId(block)
        val path = id.path

        // Hearty Grains: Reset to Age 5 and maintain both halves
        if (block == CobblemonBlocks.HEARTY_GRAINS && ageProp != null && blockState.contains(Properties.DOUBLE_BLOCK_HALF)) {
            val lowerState = blockState.with(ageProp, 5).with(Properties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER)
            val upperState = blockState.with(ageProp, 5).with(Properties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER)

            world.setBlockState(blockPos, lowerState, Block.NOTIFY_LISTENERS)
            world.setBlockState(blockPos.up(), upperState, Block.NOTIFY_LISTENERS)
            return
        }

        if (blockState.contains(Properties.DOUBLE_BLOCK_HALF)) {
            val upperPos = blockPos.up()
            if (world.getBlockState(upperPos).isOf(block)) {
                world.setBlockState(upperPos, Blocks.AIR.defaultState, Block.NOTIFY_LISTENERS)
            }
        }

        // For pumpkins and melons, just break the fruit block (don't replant)
        if (block == Blocks.PUMPKIN || block == Blocks.MELON) {
            world.setBlockState(blockPos, Blocks.AIR.defaultState, Block.NOTIFY_LISTENERS)
            return
        }

        val newState = if (config.shouldReplantCrops) {
            when {
                path == FarmersDelightBlocks.RICE_PANICLES -> Blocks.AIR.defaultState
                (path == FarmersDelightBlocks.TOMATOES || path in FarmersDelightBlocks.MUSHROOMS) && blockState.contains(AGE_3) -> blockState.with(AGE_3, 0)
                ageProp != null -> {
                    val resetAge = when {
                        isCroptopia(block) && path.contains("berry") -> {
                            (blockState.get(ageProp) - 1).coerceAtLeast(0)
                        }

                        block is SweetBerryBushBlock || block == CobblemonBlocks.GALARICA_NUT_BUSH -> 1
                        block == CobblemonBlocks.REVIVAL_HERB -> RevivalHerbBlock.MIN_AGE
                        else -> 0
                    }
                    blockState.with(ageProp, resetAge)
                }
                block is CaveVines -> blockState.with(CaveVinesBodyBlock.BERRIES, false)
                else -> Blocks.AIR.defaultState
            }
        } else {
            if (block is SweetBerryBushBlock || block == CobblemonBlocks.GALARICA_NUT_BUSH) blockState.with(AGE_3, 1) else Blocks.AIR.defaultState
        }

        world.setBlockState(blockPos, newState, Block.NOTIFY_LISTENERS)
    }

    private fun isMatureCrop(world: World, pos: BlockPos): Boolean {
        val state = world.getBlockState(pos)
        val block = state.block

        if (block == Blocks.PUMPKIN || block == Blocks.MELON) {
            return true
        }

        val ageProp = getAgeProperty(state)

        if (ageProp != null) {
            val maxAge = ageProp.values.maxOrNull() ?: 0
            return state.get(ageProp) >= maxAge
        }

        return when {
            block is HeartyGrainsBlock -> block.getAge(state) == HeartyGrainsBlock.MATURE_AGE
            block is CaveVines -> state.get(CaveVinesBodyBlock.BERRIES)
            else -> false
        }
    }

    private fun getAgeProperty(state: BlockState): IntProperty? = state.properties.filterIsInstance<IntProperty>().firstOrNull { it.name == "age" }
}