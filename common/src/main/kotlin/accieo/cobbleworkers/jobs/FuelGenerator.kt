/*
 * Copyright (C) 2025 Accieo
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package accieo.cobbleworkers.jobs

import accieo.cobbleworkers.cache.CobbleworkersCacheManager
import accieo.cobbleworkers.config.CobbleworkersConfigHolder
import accieo.cobbleworkers.enums.JobType
import accieo.cobbleworkers.interfaces.Worker
import accieo.cobbleworkers.mixin.AbstractFurnaceBlockEntityAccessor
import accieo.cobbleworkers.utilities.CobbleworkersNavigationUtils
import accieo.cobbleworkers.utilities.CobbleworkersTypeUtils
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import net.minecraft.block.AbstractFurnaceBlock
import net.minecraft.block.BlockState
import net.minecraft.block.entity.AbstractFurnaceBlockEntity
import net.minecraft.registry.Registries
import net.minecraft.state.property.BooleanProperty
import net.minecraft.util.math.BlockPos
import net.minecraft.world.World
import java.util.UUID
import kotlin.text.lowercase

object FuelGenerator : Worker {
    private val config = CobbleworkersConfigHolder.config.fuel
    private val cooldownTicks get() = config.fuelGenerationCooldownSeconds * 20L
    private val lastGenerationTime = mutableMapOf<UUID, Long>()

    override val jobType: JobType = JobType.FuelGenerator

    /**
     * Supports Vanilla Furnaces AND Cooking for Blockheads Oven.
     */
    override val blockValidator: ((World, BlockPos) -> Boolean) = { world: World, pos: BlockPos ->
        val state = world.getBlockState(pos)
        val id = Registries.BLOCK.getId(state.block).toString()
        // Match vanilla furnaces OR any cookingforblockheads block with "oven" in the name
        state.block is AbstractFurnaceBlock || (id.contains("cookingforblockheads") && id.contains("oven"))
    }

    override fun shouldRun(pokemonEntity: PokemonEntity): Boolean {
        if (!config.fuelGeneratorsEnabled) return false
        return CobbleworkersTypeUtils.isAllowedByType(config.typeGeneratesFuel, pokemonEntity) || isDesignatedGenerator(pokemonEntity)
    }

    override fun tick(world: World, origin: BlockPos, pokemonEntity: PokemonEntity) {
        handleFuelGeneration(world, origin, pokemonEntity)
    }

    /**
     * Finds closest furnace or oven nearby that needs fuel.
     */
    private fun findClosestFurnace(world: World, origin: BlockPos): BlockPos? {
        val possibleTargets = CobbleworkersCacheManager.getTargets(origin, jobType)
        if (possibleTargets.isEmpty()) return null

        return possibleTargets
            .filter { pos ->
                // 1. Check if it's a valid block type (Furnace or Oven)
                if (!blockValidator(world, pos)) return@filter false

                // 2. Check if it has items waiting to be cooked
                if (!hasItemsToSmelt(world, pos)) return@filter false

                // 3. Check if it needs fuel
                if (!needsFuel(world, pos)) return@filter false

                // 4. Ensure navigation hasn't recently failed
                !CobbleworkersNavigationUtils.isRecentlyExpired(pos, world)
            }
            .minByOrNull { it.getSquaredDistance(origin) }
    }

    /**
     * Checks for items in input slots. Supports Vanilla and CFB Oven.
     */
    private fun hasItemsToSmelt(world: World, pos: BlockPos): Boolean {
        val blockEntity = world.getBlockEntity(pos) ?: return false
        val state = world.getBlockState(pos)
        val id = Registries.BLOCK.getId(state.block).toString()

        if (id.contains("cookingforblockheads") && id.contains("oven")) {
            // Check NBT for items in the oven
            val nbt = blockEntity.createNbt(world.registryManager)
            if (nbt.contains("ItemHandler")) {
                val itemHandler = nbt.getCompound("ItemHandler")
                if (itemHandler.contains("Items")) {
                    val items = itemHandler.getList("Items", 10)
                    // Check if there are ANY items at all (any slot)
                    return items.size > 0
                }
            }
            return false
        }

        if (blockEntity is AbstractFurnaceBlockEntity) {
            return !blockEntity.getStack(0).isEmpty
        }

        return false
    }

    /**
     * Checks if the furnace/oven needs fuel.
     * Returns true if it should receive fuel from a Pokemon.
     */
    private fun needsFuel(world: World, pos: BlockPos): Boolean {
        val state = world.getBlockState(pos)
        val blockEntity = world.getBlockEntity(pos) ?: return false
        val id = Registries.BLOCK.getId(state.block).toString()

        if (id.contains("cookingforblockheads") && id.contains("oven")) {
            // For Cooking for Blockheads Oven, check NBT directly
            val nbt = blockEntity.createNbt(world.registryManager)
            val burnTime = nbt.getInt("BurnTime")

            // If actively burning with plenty of fuel (>10 seconds), don't add more
            if (burnTime > 200) return false

            // Otherwise needs fuel
            return true
        }

        // For vanilla furnaces, check if NOT currently lit
        if (state.block is AbstractFurnaceBlock) {
            try {
                state.entries.keys.find { it.name == "lit" }?.let { prop ->
                    if (prop is BooleanProperty) {
                        return state.get(prop) == false
                    }
                }
            } catch (e: Exception) {
                // If can't read lit property, check fuel slot
            }

            // Fallback: check fuel slot
            if (blockEntity is AbstractFurnaceBlockEntity) {
                return blockEntity.getStack(1).isEmpty
            }
        }

        return false
    }

    private fun handleFuelGeneration(world: World, origin: BlockPos, pokemonEntity: PokemonEntity) {
        val pokemonId = pokemonEntity.pokemon.uuid
        val now = world.time
        val lastTime = lastGenerationTime[pokemonId] ?: 0L

        if (now - lastTime < cooldownTicks) return

        val closestFurnace = findClosestFurnace(world, origin) ?: return
        val currentTarget = CobbleworkersNavigationUtils.getTarget(pokemonId, world)

        if (currentTarget == null) {
            if (!CobbleworkersNavigationUtils.isTargeted(closestFurnace, world) && !CobbleworkersNavigationUtils.isRecentlyExpired(closestFurnace, world)) {
                CobbleworkersNavigationUtils.claimTarget(pokemonId, closestFurnace, world)
            }
            return
        }

        if (currentTarget == closestFurnace) {
            CobbleworkersNavigationUtils.navigateTo(pokemonEntity, closestFurnace)
        }

        if (CobbleworkersNavigationUtils.isPokemonAtPosition(pokemonEntity, closestFurnace)) {
            if (hasItemsToSmelt(world, closestFurnace)) {
                addBurnTime(world, closestFurnace)
                lastGenerationTime[pokemonId] = now
            }
            CobbleworkersNavigationUtils.releaseTarget(pokemonId, world)
        }
    }

    private fun addBurnTime(world: World, furnacePos: BlockPos) {
        val blockEntity = world.getBlockEntity(furnacePos) ?: return
        val state = world.getBlockState(furnacePos)
        val addedBurnTime = (config.burnTimeSeconds * 20).coerceAtMost(20000)
        val registryLookup = world.registryManager
        val id = Registries.BLOCK.getId(state.block).toString()

        if (blockEntity is AbstractFurnaceBlockEntity) {
            val accessor = blockEntity as AbstractFurnaceBlockEntityAccessor
            accessor.setBurnTime(addedBurnTime)
            accessor.setFuelTime(addedBurnTime)

            state.entries.keys.find { it.name == "lit" && it is BooleanProperty }?.let { prop ->
                world.setBlockState(furnacePos, state.with(prop as BooleanProperty, true), 3)
            }
            blockEntity.markDirty()
        }
        else if (id.contains("cookingforblockheads") && id.contains("oven")) {
            try {
                // Try to use reflection to set burn time directly on the block entity
                val setBurnTimeMethod = blockEntity.javaClass.getDeclaredMethod("setBurnTime", Int::class.javaPrimitiveType)
                setBurnTimeMethod.isAccessible = true
                setBurnTimeMethod.invoke(blockEntity, addedBurnTime)
                blockEntity.markDirty()
            } catch (e: Exception) {
                // Fallback to NBT if reflection fails
                val nbt = blockEntity.createNbt(registryLookup)
                nbt.putInt("BurnTime", addedBurnTime)
                nbt.putInt("CurrentItemBurnTime", addedBurnTime)

                try {
                    blockEntity.read(nbt, registryLookup)
                } catch (ex: Exception) {
                    // If read fails, try readNbt
                    try {
                        val readNbtMethod = blockEntity.javaClass.getMethod("readNbt", net.minecraft.nbt.NbtCompound::class.java)
                        readNbtMethod.invoke(blockEntity, nbt)
                    } catch (ex2: Exception) {
                        // Last resort: direct field access
                        try {
                            val burnTimeField = blockEntity.javaClass.getDeclaredField("burnTime")
                            burnTimeField.isAccessible = true
                            burnTimeField.setInt(blockEntity, addedBurnTime)

                            val currentItemBurnTimeField = blockEntity.javaClass.getDeclaredField("currentItemBurnTime")
                            currentItemBurnTimeField.isAccessible = true
                            currentItemBurnTimeField.setInt(blockEntity, addedBurnTime)
                        } catch (ex3: Exception) {
                            // Give up
                        }
                    }
                }

                blockEntity.markDirty()
            }

            // Update the 'active' state to true
            try {
                state.entries.keys.find { p -> p.name == "active" && p is BooleanProperty }?.let { prop ->
                    world.setBlockState(furnacePos, state.with(prop as BooleanProperty, true), 3)
                }
            } catch (e: Exception) {
                // Ignore
            }

            // Force a block update
            world.updateNeighbors(furnacePos, state.block)
            world.updateListeners(furnacePos, state, state, 3)
        }
    }

    private fun isDesignatedGenerator(pokemonEntity: PokemonEntity): Boolean {
        val speciesName = pokemonEntity.pokemon.species.translatedName.string.lowercase()
        return config.fuelGenerators.any { it.lowercase() == speciesName }
    }
}