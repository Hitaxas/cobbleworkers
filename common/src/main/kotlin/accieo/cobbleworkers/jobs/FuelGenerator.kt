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
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvents
import net.minecraft.state.property.BooleanProperty
import net.minecraft.util.math.BlockPos
import net.minecraft.world.World
import java.util.UUID
import kotlin.text.lowercase

object FuelGenerator : Worker {
    private val config = CobbleworkersConfigHolder.config.fuel
    private val cooldownTicks get() = config.fuelGenerationCooldownSeconds * 20L
    private val lastGenerationTime = mutableMapOf<UUID, Long>()
    private val pokemonTendingFurnaces = mutableMapOf<UUID, BlockPos>() // Track which pokemon is tending which furnace
    private val lastSoundTime = mutableMapOf<UUID, Long>() // Track last time fire sound was played
    private const val SOUND_INTERVAL = 30L // Play fire sound every 1.5 seconds

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
            // Oven slot layout:
            // 0-2: Input (raw food)
            // 3: Fuel
            // 4-6: Output (cooked food) - IGNORE THESE
            // 7-15: Processing buffer (items being cooked)
            // 16-19: Tools
            val nbt = blockEntity.createNbt(world.registryManager)
            if (nbt.contains("ItemHandler")) {
                val itemHandler = nbt.getCompound("ItemHandler")
                if (itemHandler.contains("Items")) {
                    val items = itemHandler.getList("Items", 10)
                    for (i in 0 until items.size) {
                        val itemStackNbt = items.getCompound(i)
                        if (itemStackNbt.contains("Slot") && itemStackNbt.contains("id")) {
                            val slot = itemStackNbt.getByte("Slot").toInt()
                            // Only check INPUT slots (0-2) and PROCESSING slots (7-15)
                            // IGNORE output slots (4-6) where finished cooked food sits
                            if (slot in 0..2 || slot in 7..15) {
                                return true
                            }
                        }
                    }
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

    /**
     * Checks if furnace/oven is currently burning and has items cooking.
     */
    private fun isCooking(world: World, pos: BlockPos): Boolean {
        val blockEntity = world.getBlockEntity(pos) ?: return false
        val state = world.getBlockState(pos)
        val id = Registries.BLOCK.getId(state.block).toString()

        if (id.contains("cookingforblockheads") && id.contains("oven")) {
            val nbt = blockEntity.createNbt(world.registryManager)
            val burnTime = nbt.getInt("BurnTime")

            // Check if burning and has items in processing slots
            if (burnTime > 0) {
                if (nbt.contains("ItemHandler")) {
                    val itemHandler = nbt.getCompound("ItemHandler")
                    if (itemHandler.contains("Items")) {
                        val items = itemHandler.getList("Items", 10)
                        for (i in 0 until items.size) {
                            val itemStackNbt = items.getCompound(i)
                            if (itemStackNbt.contains("Slot") && itemStackNbt.contains("id")) {
                                val slot = itemStackNbt.getByte("Slot").toInt()
                                // Check if items are in INPUT (0-2) or PROCESSING (7-15) slots
                                if (slot in 0..2 || slot in 7..15) {
                                    return true
                                }
                            }
                        }
                    }
                }
            }
            return false
        }

        // For vanilla furnaces, check if lit and has items
        if (state.block is AbstractFurnaceBlock) {
            try {
                state.entries.keys.find { it.name == "lit" }?.let { prop ->
                    if (prop is BooleanProperty && state.get(prop) == true) {
                        if (blockEntity is AbstractFurnaceBlockEntity) {
                            return !blockEntity.getStack(0).isEmpty
                        }
                    }
                }
            } catch (e: Exception) {
                return false
            }
        }

        return false
    }

    /**
     * Plays fire crackling sound near the furnace.
     */
    private fun playFireSound(world: World, pos: BlockPos) {
        if (world is ServerWorld) {
            world.playSound(
                null,
                pos,
                SoundEvents.BLOCK_FIRE_AMBIENT,
                SoundCategory.BLOCKS,
                0.5f,
                1.0f
            )
        }
    }

    private fun handleFuelGeneration(world: World, origin: BlockPos, pokemonEntity: PokemonEntity) {
        val pokemonId = pokemonEntity.pokemon.uuid
        val now = world.time
        val tendingPos = pokemonTendingFurnaces[pokemonId]

        // If pokemon is tending a furnace, check if it's still cooking
        if (tendingPos != null) {
            // Check if furnace still exists and is valid
            if (!blockValidator(world, tendingPos) || !isCooking(world, tendingPos)) {
                // Cooking done or furnace removed, release
                pokemonTendingFurnaces.remove(pokemonId)
                lastSoundTime.remove(pokemonId)
                CobbleworkersNavigationUtils.releaseTarget(pokemonId, world)
                return
            }

            // Stop any navigation and make pokemon stand still facing the furnace
            pokemonEntity.navigation.stop()

            // Make pokemon look at the furnace
            val furnaceCenter = tendingPos.toCenterPos()
            val dx = furnaceCenter.x - pokemonEntity.x
            val dz = furnaceCenter.z - pokemonEntity.z
            val yaw = (Math.atan2(dz, dx) * 180.0 / Math.PI).toFloat() - 90.0f
            pokemonEntity.headYaw = yaw
            pokemonEntity.bodyYaw = yaw
            pokemonEntity.yaw = yaw

            // Play fire sound periodically
            val lastSound = lastSoundTime[pokemonId] ?: 0L
            if (now - lastSound >= SOUND_INTERVAL) {
                playFireSound(world, tendingPos)
                lastSoundTime[pokemonId] = now
            }
            return
        }

        // Not tending anything, check cooldown
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

                // If furnace is now cooking, mark pokemon as tending it
                if (isCooking(world, closestFurnace)) {
                    pokemonTendingFurnaces[pokemonId] = closestFurnace
                    lastSoundTime[pokemonId] = now

                    // Position pokemon in front of furnace/oven facing it
                    positionPokemonAtFurnace(world, closestFurnace, pokemonEntity)
                    playFireSound(world, closestFurnace)
                    // Don't release target - keep it claimed while tending
                } else {
                    CobbleworkersNavigationUtils.releaseTarget(pokemonId, world)
                }
            } else {
                CobbleworkersNavigationUtils.releaseTarget(pokemonId, world)
            }
        }
    }

    /**
     * Positions the Pokemon in front of the furnace/oven, facing it.
     */
    private fun positionPokemonAtFurnace(world: World, furnacePos: BlockPos, pokemonEntity: PokemonEntity) {
        val state = world.getBlockState(furnacePos)

        // Try to get the facing direction of the furnace/oven
        val facing = try {
            state.entries.keys.find { it.name == "facing" }?.let { prop ->
                state.get(prop) as? net.minecraft.util.math.Direction
            }
        } catch (e: Exception) {
            null
        } ?: net.minecraft.util.math.Direction.NORTH // Default to north if can't determine

        // Position pokemon 1.5 blocks in front of the furnace
        val offset = facing.vector
        val targetPos = furnacePos.add(offset.x * 2, 0, offset.z * 2)
        val targetCenter = targetPos.toCenterPos()

        // Teleport pokemon to position in front of furnace
        pokemonEntity.refreshPositionAndAngles(targetCenter.x, pokemonEntity.y, targetCenter.z, pokemonEntity.yaw, pokemonEntity.pitch)

        // Make pokemon face the furnace
        val furnaceCenter = furnacePos.toCenterPos()
        val dx = furnaceCenter.x - pokemonEntity.x
        val dz = furnaceCenter.z - pokemonEntity.z
        val yaw = (Math.atan2(dz, dx) * 180.0 / Math.PI).toFloat() - 90.0f
        pokemonEntity.headYaw = yaw
        pokemonEntity.bodyYaw = yaw
        pokemonEntity.yaw = yaw

        // Stop all movement
        pokemonEntity.navigation.stop()
        pokemonEntity.setVelocity(0.0, pokemonEntity.velocity.y, 0.0)
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
                            val burnTimeField = blockEntity.javaClass.getDeclaredField("furnaceBurnTime")
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