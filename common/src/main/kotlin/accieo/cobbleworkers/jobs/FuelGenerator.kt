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
import accieo.cobbleworkers.sanity.SanityManager
import accieo.cobbleworkers.utilities.CobbleworkersNavigationUtils
import accieo.cobbleworkers.utilities.CobbleworkersTypeUtils
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import net.minecraft.block.AbstractFurnaceBlock
import net.minecraft.block.entity.AbstractFurnaceBlockEntity
import net.minecraft.nbt.NbtCompound
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
    private val pokemonTendingFurnaces = mutableMapOf<UUID, BlockPos>()
    private val lastSoundTime = mutableMapOf<UUID, Long>()
    private val lastCookingSpeedBoost = mutableMapOf<BlockPos, Long>()
    private const val SOUND_INTERVAL = 30L
    private const val COOKING_SPEED_BOOST_INTERVAL = 2L

    override val jobType: JobType = JobType.FuelGenerator

    fun isPokemonTending(pokemonUuid: UUID): Boolean {
        return pokemonTendingFurnaces.containsKey(pokemonUuid)
    }

    override val blockValidator: ((World, BlockPos) -> Boolean) = { world: World, pos: BlockPos ->
        val state = world.getBlockState(pos)
        val id = Registries.BLOCK.getId(state.block).toString()
        state.block is AbstractFurnaceBlock || (id.contains("cookingforblockheads") && id.contains("oven"))
    }

    override fun shouldRun(pokemonEntity: PokemonEntity): Boolean {
        if (!config.fuelGeneratorsEnabled) return false
        return CobbleworkersTypeUtils.isAllowedByType(config.typeGeneratesFuel, pokemonEntity) || isDesignatedGenerator(pokemonEntity)
    }

    override fun tick(world: World, origin: BlockPos, pokemonEntity: PokemonEntity) {
        handleFuelGeneration(world, origin, pokemonEntity)
    }

    private fun interruptWork(pokemonEntity: PokemonEntity, world: World) {
        val uuid = pokemonEntity.pokemon.uuid
        val tendingPos = pokemonTendingFurnaces[uuid]
        if (tendingPos != null) {
            lastCookingSpeedBoost.remove(tendingPos)
        }
        pokemonTendingFurnaces.remove(uuid)
        lastSoundTime.remove(uuid)
        CobbleworkersNavigationUtils.releaseTarget(uuid, world)
    }

    private fun isLegendaryOrMythical(pokemonEntity: PokemonEntity): Boolean {
        val labels = pokemonEntity.pokemon.species.labels
        return labels.contains("legendary") || labels.contains("mythical")
    }

    private fun handleFuelGeneration(world: World, origin: BlockPos, pokemonEntity: PokemonEntity) {
        val pokemonId = pokemonEntity.pokemon.uuid
        val now = world.time
        val tendingPos = pokemonTendingFurnaces[pokemonId]

        if (SanityManager.needsForcedBreak(pokemonEntity)) {
            interruptWork(pokemonEntity, world)
            return
        }

        if (tendingPos != null) {
            forceStandPose(pokemonEntity)

            if (!blockValidator(world, tendingPos)) {
                interruptWork(pokemonEntity, world)
                return
            }

            if (!isCooking(world, tendingPos)) {
                if (hasItemsToSmelt(world, tendingPos) && needsFuel(world, tendingPos)) {
                    addBurnTime(world, tendingPos)
                    lastGenerationTime[pokemonId] = now

                    if (isCooking(world, tendingPos)) {
                        lastSoundTime[pokemonId] = now
                        playFireSound(world, tendingPos)
                    } else {
                        interruptWork(pokemonEntity, world)
                    }

                    return
                }

                interruptWork(pokemonEntity, world)
                return
            }

            if (isLegendaryOrMythical(pokemonEntity)) {
                val lastBoost = lastCookingSpeedBoost[tendingPos] ?: 0L
                if (now - lastBoost >= COOKING_SPEED_BOOST_INTERVAL) {
                    boostCookingSpeed(world, tendingPos)
                    lastCookingSpeedBoost[tendingPos] = now
                }
            }

            pokemonEntity.navigation.stop()

            val furnaceCenter = tendingPos.toCenterPos()
            val dx = furnaceCenter.x - pokemonEntity.x
            val dz = furnaceCenter.z - pokemonEntity.z
            val yaw = (Math.atan2(dz, dx) * 180.0 / Math.PI).toFloat() - 90.0f
            pokemonEntity.headYaw = yaw
            pokemonEntity.bodyYaw = yaw
            pokemonEntity.yaw = yaw

            val lastSound = lastSoundTime[pokemonId] ?: 0L
            if (now - lastSound >= SOUND_INTERVAL) {
                playFireSound(world, tendingPos)
                lastSoundTime[pokemonId] = now
            }

            spawnFlamethrowerParticles(world, pokemonEntity, tendingPos)
            return
        }

        val lastTime = lastGenerationTime[pokemonId] ?: 0L
        if (now - lastTime < cooldownTicks) return

        val closestFurnace = findClosestFurnace(world, origin) ?: return
        val currentTarget = CobbleworkersNavigationUtils.getTarget(pokemonId, world)

        if (currentTarget == null) {
            if (!CobbleworkersNavigationUtils.isTargeted(closestFurnace, world) &&
                !CobbleworkersNavigationUtils.isRecentlyExpired(closestFurnace, world) &&
                !pokemonTendingFurnaces.values.contains(closestFurnace)) {
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

                if (isCooking(world, closestFurnace)) {
                    pokemonTendingFurnaces[pokemonId] = closestFurnace
                    lastSoundTime[pokemonId] = now
                    positionPokemonAtFurnace(world, closestFurnace, pokemonEntity)
                    playFireSound(world, closestFurnace)
                } else {
                    CobbleworkersNavigationUtils.releaseTarget(pokemonId, world)
                }
            } else {
                CobbleworkersNavigationUtils.releaseTarget(pokemonId, world)
            }
        }
    }

    private fun boostCookingSpeed(world: World, furnacePos: BlockPos) {
        val blockEntity = world.getBlockEntity(furnacePos) ?: return
        val state = world.getBlockState(furnacePos)
        val id = Registries.BLOCK.getId(state.block).toString()

        if (blockEntity is AbstractFurnaceBlockEntity) {
            try {
                val cookTimeField = blockEntity.javaClass.getDeclaredField("cookTime")
                cookTimeField.isAccessible = true
                val currentCookTime = cookTimeField.getInt(blockEntity)

                val cookTimeTotalField = blockEntity.javaClass.getDeclaredField("cookTimeTotal")
                cookTimeTotalField.isAccessible = true
                val cookTimeTotal = cookTimeTotalField.getInt(blockEntity)

                if (currentCookTime > 0 && currentCookTime < cookTimeTotal) {
                    val newCookTime = (currentCookTime + 2).coerceAtMost(cookTimeTotal)
                    cookTimeField.setInt(blockEntity, newCookTime)
                    blockEntity.markDirty()
                }
            } catch (e: Exception) {
                try {
                    val nbt = blockEntity.createNbt(world.registryManager)
                    val currentCookTime = nbt.getShort("CookTime").toInt()
                    val cookTimeTotal = nbt.getShort("CookTimeTotal").toInt()

                    if (currentCookTime > 0 && currentCookTime < cookTimeTotal) {
                        val newCookTime = (currentCookTime + 2).coerceAtMost(cookTimeTotal)
                        nbt.putShort("CookTime", newCookTime.toShort())
                        blockEntity.read(nbt, world.registryManager)
                        blockEntity.markDirty()
                    }
                } catch (_: Exception) {}
            }
        } else if (id.contains("cookingforblockheads") && id.contains("oven")) {
            try {
                val nbt = blockEntity.createNbt(world.registryManager)
                var modified = false

                if (nbt.contains("CookTimes")) {
                    val cookTimes = nbt.getIntArray("CookTimes")
                    val newCookTimes = IntArray(cookTimes.size)

                    val maxCookTimes = if (nbt.contains("MaxCookTimes")) {
                        nbt.getIntArray("MaxCookTimes")
                    } else {
                        IntArray(cookTimes.size) { 200 }
                    }

                    for (i in cookTimes.indices) {
                        val currentCookTime = cookTimes[i]
                        val maxCookTime = if (i < maxCookTimes.size) maxCookTimes[i] else 200

                        if (currentCookTime > 0 && currentCookTime < maxCookTime - 3) {
                            newCookTimes[i] = (currentCookTime + 2).coerceAtMost(maxCookTime - 2)
                            modified = true
                        } else {
                            newCookTimes[i] = currentCookTime
                        }
                    }

                    if (modified) {
                        nbt.putIntArray("CookTimes", newCookTimes)

                        try {
                            blockEntity.read(nbt, world.registryManager)
                        } catch (ex: Exception) {
                            try {
                                val readNbtMethod = blockEntity.javaClass.getMethod("readNbt", NbtCompound::class.java)
                                readNbtMethod.invoke(blockEntity, nbt)
                            } catch (ex2: Exception) {
                                try {
                                    val cookTimesField = blockEntity.javaClass.getDeclaredField("cookTimes")
                                    cookTimesField.isAccessible = true
                                    cookTimesField.set(blockEntity, newCookTimes)
                                } catch (_: Exception) {}
                            }
                        }
                        blockEntity.markDirty()
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun findClosestFurnace(world: World, origin: BlockPos): BlockPos? {
        val possibleTargets = CobbleworkersCacheManager.getTargets(origin, jobType)
        if (possibleTargets.isEmpty()) return null

        return possibleTargets
            .filter { pos ->
                if (!blockValidator(world, pos)) return@filter false
                if (!hasItemsToSmelt(world, pos)) return@filter false
                if (!needsFuel(world, pos)) return@filter false
                !CobbleworkersNavigationUtils.isRecentlyExpired(pos, world)
            }
            .minByOrNull { it.getSquaredDistance(origin) }
    }

    private fun hasItemsToSmelt(world: World, pos: BlockPos): Boolean {
        val blockEntity = world.getBlockEntity(pos) ?: return false
        val state = world.getBlockState(pos)
        val id = Registries.BLOCK.getId(state.block).toString()

        if (id.contains("cookingforblockheads") && id.contains("oven")) {
            val nbt = blockEntity.createNbt(world.registryManager)
            if (nbt.contains("ItemHandler")) {
                val itemHandler = nbt.getCompound("ItemHandler")
                if (itemHandler.contains("Items")) {
                    val items = itemHandler.getList("Items", 10)
                    for (i in 0 until items.size) {
                        val itemStackNbt = items.getCompound(i)
                        if (itemStackNbt.contains("Slot") && itemStackNbt.contains("id")) {
                            val slot = itemStackNbt.getByte("Slot").toInt()
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

    private fun needsFuel(world: World, pos: BlockPos): Boolean {
        val state = world.getBlockState(pos)
        val blockEntity = world.getBlockEntity(pos) ?: return false
        val id = Registries.BLOCK.getId(state.block).toString()

        if (id.contains("cookingforblockheads") && id.contains("oven")) {
            val nbt = blockEntity.createNbt(world.registryManager)
            val burnTime = nbt.getInt("BurnTime")
            if (burnTime > 200) return false
            return true
        }

        if (state.block is AbstractFurnaceBlock) {
            try {
                state.entries.keys.find { it.name == "lit" }?.let { prop ->
                    if (prop is BooleanProperty) {
                        return state.get(prop) == false
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }

            if (blockEntity is AbstractFurnaceBlockEntity) {
                return blockEntity.getStack(1).isEmpty
            }
        }

        return false
    }

    private fun isCooking(world: World, pos: BlockPos): Boolean {
        val blockEntity = world.getBlockEntity(pos) ?: return false
        val state = world.getBlockState(pos)
        val id = Registries.BLOCK.getId(state.block).toString()

        if (id.contains("cookingforblockheads") && id.contains("oven")) {
            val nbt = blockEntity.createNbt(world.registryManager)
            val burnTime = nbt.getInt("BurnTime")

            if (burnTime > 0) {
                if (nbt.contains("ItemHandler")) {
                    val itemHandler = nbt.getCompound("ItemHandler")
                    if (itemHandler.contains("Items")) {
                        val items = itemHandler.getList("Items", 10)
                        for (i in 0 until items.size) {
                            val itemStackNbt = items.getCompound(i)
                            if (itemStackNbt.contains("Slot") && itemStackNbt.contains("id")) {
                                val slot = itemStackNbt.getByte("Slot").toInt()
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

    private fun playFireSound(world: World, pos: BlockPos) {
        if (world is ServerWorld) {
            world.playSound(null, pos, SoundEvents.BLOCK_FIRE_AMBIENT, SoundCategory.BLOCKS, 0.5f, 1.0f)
        }
    }

    private fun spawnFlamethrowerParticles(world: World, pokemonEntity: PokemonEntity, furnacePos: BlockPos) {
        if (world !is ServerWorld) return

        val headHeight = try {
            pokemonEntity.getEyeY() - pokemonEntity.y
        } catch (e: Exception) {
            pokemonEntity.standingEyeHeight.toDouble()
        }

        val startX = pokemonEntity.x
        val startY = pokemonEntity.y + headHeight
        val startZ = pokemonEntity.z

        val furnaceCenter = furnacePos.toCenterPos()
        val dx = furnaceCenter.x - startX
        val dy = (furnaceCenter.y - 0.5) - startY
        val dz = furnaceCenter.z - startZ

        val distance = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)

        val dirX = dx / distance
        val dirY = dy / distance
        val dirZ = dz / distance

        repeat(8) {
            val spread = 0.15
            val vX = dirX + (world.random.nextDouble() - 0.5) * spread
            val vY = dirY + (world.random.nextDouble() - 0.5) * spread
            val vZ = dirZ + (world.random.nextDouble() - 0.5) * spread

            val speedBase = 0.35
            val speedVar = world.random.nextDouble() * 0.2
            val finalSpeed = speedBase + speedVar

            world.spawnParticles(
                net.minecraft.particle.ParticleTypes.FLAME,
                startX,
                startY,
                startZ,
                0,
                vX,
                vY,
                vZ,
                finalSpeed
            )
        }

        if (world.random.nextFloat() < 0.4f) {
            world.spawnParticles(
                net.minecraft.particle.ParticleTypes.SMOKE,
                startX, startY, startZ,
                0,
                dirX * 0.2, dirY * 0.2, dirZ * 0.2,
                0.5
            )
        }
    }

    private fun positionPokemonAtFurnace(world: World, furnacePos: BlockPos, pokemonEntity: PokemonEntity) {
        val state = world.getBlockState(furnacePos)

        val facing = try {
            state.entries.keys.find { it.name == "facing" }?.let { prop ->
                state.get(prop) as? net.minecraft.util.math.Direction
            }
        } catch (e: Exception) {
            null
        } ?: net.minecraft.util.math.Direction.NORTH

        val offset = facing.vector
        val targetPos = furnacePos.add(offset.x * 2, 0, offset.z * 2)
        val targetCenter = targetPos.toCenterPos()

        pokemonEntity.refreshPositionAndAngles(targetCenter.x, pokemonEntity.y, targetCenter.z, pokemonEntity.yaw, pokemonEntity.pitch)

        val furnaceCenter = furnacePos.toCenterPos()
        val dx = furnaceCenter.x - pokemonEntity.x
        val dz = furnaceCenter.z - pokemonEntity.z
        val yaw = (Math.atan2(dz, dx) * 180.0 / Math.PI).toFloat() - 90.0f
        pokemonEntity.headYaw = yaw
        pokemonEntity.bodyYaw = yaw
        pokemonEntity.yaw = yaw

        pokemonEntity.navigation.stop()
        pokemonEntity.setVelocity(0.0, pokemonEntity.velocity.y, 0.0)
    }

    private fun forceStandPose(pokemonEntity: PokemonEntity) {
        val nbt = pokemonEntity.writeNbt(NbtCompound())
        nbt.putString("PoseType", "STAND")
        pokemonEntity.readNbt(nbt)
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
        } else if (id.contains("cookingforblockheads") && id.contains("oven")) {
            try {
                val setBurnTimeMethod = blockEntity.javaClass.getDeclaredMethod("setBurnTime", Int::class.javaPrimitiveType)
                setBurnTimeMethod.isAccessible = true
                setBurnTimeMethod.invoke(blockEntity, addedBurnTime)
                blockEntity.markDirty()
            } catch (e: Exception) {
                val nbt = blockEntity.createNbt(registryLookup)
                nbt.putInt("BurnTime", addedBurnTime)
                nbt.putInt("CurrentItemBurnTime", addedBurnTime)

                try {
                    blockEntity.read(nbt, registryLookup)
                } catch (ex: Exception) {
                    try {
                        val readNbtMethod = blockEntity.javaClass.getMethod("readNbt", net.minecraft.nbt.NbtCompound::class.java)
                        readNbtMethod.invoke(blockEntity, nbt)
                    } catch (ex2: Exception) {
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

            try {
                state.entries.keys.find { p -> p.name == "active" && p is BooleanProperty }?.let { prop ->
                    world.setBlockState(furnacePos, state.with(prop as BooleanProperty, true), 3)
                }
            } catch (e: Exception) {
                // Ignore
            }

            world.updateNeighbors(furnacePos, state.block)
            world.updateListeners(furnacePos, state, state, 3)
        }
    }

    private fun isDesignatedGenerator(pokemonEntity: PokemonEntity): Boolean {
        val speciesName = pokemonEntity.pokemon.species.translatedName.string.lowercase()
        return config.fuelGenerators.any { it.lowercase() == speciesName }
    }

    override fun isActivelyWorking(pokemon: PokemonEntity): Boolean {
        val uuid = pokemon.pokemon.uuid
        val pos = pokemonTendingFurnaces[uuid] ?: return false

        if (
            !blockValidator(pokemon.world, pos) ||
            !isCooking(pokemon.world, pos)
        ) {
            pokemonTendingFurnaces.remove(uuid)
            lastSoundTime.remove(uuid)
            lastCookingSpeedBoost.remove(pos)
            return false
        }

        return true
    }

    override fun interrupt(pokemonEntity: PokemonEntity, world: World) {
        try {
            interruptWork(pokemonEntity, world)
        } catch (_: Exception) {}
    }
}