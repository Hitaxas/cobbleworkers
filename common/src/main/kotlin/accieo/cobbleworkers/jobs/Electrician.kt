/*
 * Copyright (C) 2025 HitaxasTV
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
import accieo.cobbleworkers.sanity.SanityManager
import accieo.cobbleworkers.utilities.CobbleworkersNavigationUtils
import accieo.cobbleworkers.utilities.CobbleworkersTypeUtils
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import net.minecraft.nbt.NbtCompound
import net.minecraft.particle.ParticleTypes
import net.minecraft.registry.Registries
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvents
import net.minecraft.state.property.BooleanProperty
import net.minecraft.util.math.BlockPos
import net.minecraft.world.World
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object Electrician : Worker {

    private val config get() = CobbleworkersConfigHolder.config.electricians
    private val pokemonPoweringBlocks = ConcurrentHashMap<UUID, BlockPos>()
    private val lastSoundTime = ConcurrentHashMap<UUID, Long>()
    private val lastPowerTime = ConcurrentHashMap<UUID, Long>()
    private val lastCookingSpeedBoost = ConcurrentHashMap<BlockPos, Long>()

    private val cooldownTicks get() = config.ticksPerCharge.toLong()
    private const val SOUND_INTERVAL = 30L
    private const val COOKING_SPEED_BOOST_INTERVAL = 2L

    override val jobType: JobType = JobType.Electrician

    override val blockValidator: ((World, BlockPos) -> Boolean) = { world: World, pos: BlockPos ->
        val state = world.getBlockState(pos)
        val id = Registries.BLOCK.getId(state.block).toString()
        val isOven = id.contains("cookingforblockheads") && id.contains("oven")
        val isAccumulator = id.contains("createaddition") && id.contains("accumulator")

        isOven || isAccumulator
    }

    override fun shouldRun(pokemonEntity: PokemonEntity): Boolean {
        if (!accieo.cobbleworkers.utilities.CobbleworkersWorkToggle.canWork(pokemonEntity.pokemon)) return false
        if (!config.electriciansEnabled) return false
        return CobbleworkersTypeUtils.isAllowedByType(config.typePowersOvens, pokemonEntity) || isDesignatedElectrician(pokemonEntity)
    }

    override fun tick(world: World, origin: BlockPos, pokemonEntity: PokemonEntity) {
        handleElectricalPowering(world, origin, pokemonEntity)
    }

    private fun interruptWork(pokemonEntity: PokemonEntity, world: World) {
        val uuid = pokemonEntity.pokemon.uuid
        val tendingPos = pokemonPoweringBlocks[uuid]
        if (tendingPos != null) lastCookingSpeedBoost.remove(tendingPos)
        pokemonPoweringBlocks.remove(uuid)
        lastSoundTime.remove(uuid)
        CobbleworkersNavigationUtils.releaseTarget(uuid, world)
    }

    private fun isLegendaryOrMythical(pokemonEntity: PokemonEntity): Boolean {
        return pokemonEntity.pokemon.species.labels.any { it == "legendary" || it == "mythical" }
    }

    private fun handleElectricalPowering(world: World, origin: BlockPos, pokemonEntity: PokemonEntity) {
        val pokemonId = pokemonEntity.pokemon.uuid
        val now = world.time
        val poweringPos = pokemonPoweringBlocks[pokemonId]

        if (SanityManager.needsForcedBreak(pokemonEntity)) {
            interruptWork(pokemonEntity, world)
            return
        }

        if (poweringPos != null) {
            forceStandPose(pokemonEntity)
            if (!blockValidator(world, poweringPos)) {
                interruptWork(pokemonEntity, world)
                return
            }

            val id = Registries.BLOCK.getId(world.getBlockState(poweringPos).block).toString()
            val isOven = id.contains("oven")

            val stillNeedsPower = needsPower(world, poweringPos)
            val stillCooking = if (isOven) isCooking(world, poweringPos) else false
            val isAccumulator = !isOven && id.contains("accumulator")
            val shouldStay = if (isAccumulator) {
                !isFullyCharged(world, poweringPos)
            } else {
                stillNeedsPower || stillCooking
            }

            if (!shouldStay) {
                interruptWork(pokemonEntity, world)
                return
            }

            pokemonEntity.navigation.stop()
            lookAtBlock(pokemonEntity, poweringPos)

            if (isOven && isLegendaryOrMythical(pokemonEntity) && stillCooking) {
                val lastBoost = lastCookingSpeedBoost[poweringPos] ?: 0L
                if (now - lastBoost >= COOKING_SPEED_BOOST_INTERVAL) {
                    addEnergyToBlock(world, poweringPos, true)
                    lastCookingSpeedBoost[poweringPos] = now
                }
            }

            val lastPower = lastPowerTime[pokemonId] ?: 0L
            val effectiveCooldown = if (isLegendaryOrMythical(pokemonEntity)) cooldownTicks / 2 else cooldownTicks

            if (stillNeedsPower && now - lastPower >= effectiveCooldown) {
                addEnergyToBlock(world, poweringPos, false)
                lastPowerTime[pokemonId] = now
            }

            if (now - (lastSoundTime[pokemonId] ?: 0L) >= SOUND_INTERVAL) {
                playElectricSound(world, poweringPos)
                lastSoundTime[pokemonId] = now
            }
            spawnElectricParticles(world, pokemonEntity, poweringPos)
            return
        }

        val lastTime = lastPowerTime[pokemonId] ?: 0L
        val searchCooldown = if (isLegendaryOrMythical(pokemonEntity)) cooldownTicks / 2 else cooldownTicks
        if (now - lastTime < searchCooldown) return

        val closestTarget = findClosestTarget(world, origin) ?: return
        val currentTarget = CobbleworkersNavigationUtils.getTarget(pokemonId, world)

        if (currentTarget == null) {
            if (!CobbleworkersNavigationUtils.isTargeted(closestTarget, world) &&
                !CobbleworkersNavigationUtils.isRecentlyExpired(closestTarget, world) &&
                !pokemonPoweringBlocks.values.contains(closestTarget)) {
                CobbleworkersNavigationUtils.claimTarget(pokemonId, closestTarget, world)
            }
            return
        }

        if (currentTarget == closestTarget) CobbleworkersNavigationUtils.navigateTo(pokemonEntity, closestTarget)

        if (CobbleworkersNavigationUtils.isPokemonAtPosition(pokemonEntity, closestTarget)) {
            val id = Registries.BLOCK.getId(world.getBlockState(closestTarget).block).toString()
            val canWork = if (id.contains("oven")) {
                hasItemsToCook(world, closestTarget) && (needsPower(world, closestTarget) || isCooking(world, closestTarget))
            } else {
                needsPower(world, closestTarget)
            }

            if (canWork) {
                pokemonPoweringBlocks[pokemonId] = closestTarget
                positionPokemonAtBlock(world, closestTarget, pokemonEntity)
            } else {
                CobbleworkersNavigationUtils.releaseTarget(pokemonId, world)
            }
        }
    }

    private fun needsPower(world: World, pos: BlockPos): Boolean {
        val blockEntity = world.getBlockEntity(pos) ?: return false
        val nbt = blockEntity.createNbt(world.registryManager)
        val id = Registries.BLOCK.getId(world.getBlockState(pos).block).toString()

        return if (id.contains("oven")) {
            nbt.getBoolean("HasPowerUpgrade") && nbt.getInt("EnergyStored") < 8000
        } else if (id.contains("accumulator")) {
            if (nbt.contains("EnergyContent")) {
                val content = nbt.getCompound("EnergyContent")
                val energy = content.getInt("energy")
                val capacity = nbt.getLong("EnergyCapacity").toInt()
                energy < capacity
            } else false
        } else {
            false
        }
    }

    private fun shouldStartChargingAccumulator(world: World, pos: BlockPos): Boolean {
        val blockEntity = world.getBlockEntity(pos) ?: return false
        val nbt = blockEntity.createNbt(world.registryManager)
        if (!nbt.contains("EnergyContent")) return false

        val energy = nbt.getCompound("EnergyContent").getInt("energy")
        val capacity = nbt.getLong("EnergyCapacity").toInt()

        return energy <= capacity * 0.5
    }


    private fun isFullyCharged(world: World, pos: BlockPos): Boolean {
        val blockEntity = world.getBlockEntity(pos) ?: return false
        val nbt = blockEntity.createNbt(world.registryManager)
        val id = Registries.BLOCK.getId(world.getBlockState(pos).block).toString()

        if (id.contains("accumulator") && nbt.contains("EnergyContent")) {
            val content = nbt.getCompound("EnergyContent")
            val energy = content.getInt("energy")
            val capacity = nbt.getLong("EnergyCapacity").toInt()
            return energy >= (capacity * 0.99)
        }
        return false
    }

    private fun addEnergyToBlock(world: World, pos: BlockPos, applyBoost: Boolean) {
        val blockEntity = world.getBlockEntity(pos) ?: return
        val registryLookup = world.registryManager
        val nbt = blockEntity.createNbt(registryLookup)
        val id = Registries.BLOCK.getId(world.getBlockState(pos).block).toString()

        if (id.contains("oven")) {
            val currentEnergy = nbt.getInt("EnergyStored")
            nbt.putInt("EnergyStored", (currentEnergy + config.fePerCharge / 10).coerceAtMost(10000))

            if (applyBoost && nbt.contains("CookTimes")) {
                val cookTimes = nbt.getIntArray("CookTimes")
                val maxCookTimes = if (nbt.contains("MaxCookTimes")) nbt.getIntArray("MaxCookTimes") else IntArray(cookTimes.size) { 200 }
                var modified = false
                for (i in cookTimes.indices) {
                    val current = cookTimes[i]
                    val max = if (i < maxCookTimes.size) maxCookTimes[i] else 200
                    if (current in 1 until max - 3) {
                        cookTimes[i] = (current + 5).coerceAtMost(max - 2)
                        modified = true
                    }
                }
                if (modified) nbt.putIntArray("CookTimes", cookTimes)
            }
        } else if (id.contains("accumulator")) {
            if (nbt.contains("EnergyContent")) {
                val content = nbt.getCompound("EnergyContent")
                val currentEnergy = content.getInt("energy")
                val capacity = nbt.getLong("EnergyCapacity").toInt()
                val newEnergy = (currentEnergy + config.fePerCharge).coerceAtMost(capacity)
                content.putInt("energy", newEnergy)
            }
        }

        try {
            blockEntity.read(nbt, registryLookup)
        } catch (_: Exception) {
            try {
                val readNbtMethod = blockEntity.javaClass.getMethod("readNbt", NbtCompound::class.java)
                readNbtMethod.invoke(blockEntity, nbt)
            } catch (_: Exception) {}
        }
        blockEntity.markDirty()
        world.updateListeners(pos, world.getBlockState(pos), world.getBlockState(pos), 3)
    }

    private fun isCooking(world: World, pos: BlockPos): Boolean {
        val blockEntity = world.getBlockEntity(pos) ?: return false
        val nbt = blockEntity.createNbt(world.registryManager)
        return if (nbt.contains("CookTimes")) nbt.getIntArray("CookTimes").any { it > 0 } else false
    }

    private fun hasItemsToCook(world: World, pos: BlockPos): Boolean {
        val blockEntity = world.getBlockEntity(pos) ?: return false
        val nbt = blockEntity.createNbt(world.registryManager)
        if (nbt.contains("ItemHandler")) {
            val items = nbt.getCompound("ItemHandler").getList("Items", 10)
            for (i in 0 until items.size) {
                val itemStackNbt = items.getCompound(i)
                if (itemStackNbt.contains("Slot")) {
                    val slot = itemStackNbt.getByte("Slot").toInt()
                    if (slot in 0..2 || slot in 7..15) return true
                }
            }
        }
        return false
    }

    private fun lookAtBlock(pokemonEntity: PokemonEntity, pos: BlockPos) {
        val center = pos.toCenterPos()
        val dx = center.x - pokemonEntity.x
        val dz = center.z - pokemonEntity.z
        val yaw = (Math.atan2(dz, dx) * 180.0 / Math.PI).toFloat() - 90.0f
        pokemonEntity.headYaw = yaw
        pokemonEntity.bodyYaw = yaw
        pokemonEntity.yaw = yaw
    }

    private fun positionPokemonAtBlock(world: World, pos: BlockPos, pokemonEntity: PokemonEntity) {
        val state = world.getBlockState(pos)
        val facing = state.entries.keys.find { it.name == "facing" }?.let { state.get(it) as? net.minecraft.util.math.Direction } ?: net.minecraft.util.math.Direction.NORTH
        val offset = facing.vector
        val targetCenter = pos.add(offset.x * 2, 0, offset.z * 2).toCenterPos()
        pokemonEntity.refreshPositionAndAngles(targetCenter.x, pokemonEntity.y, targetCenter.z, pokemonEntity.yaw, pokemonEntity.pitch)
        lookAtBlock(pokemonEntity, pos)
        pokemonEntity.navigation.stop()
        pokemonEntity.setVelocity(0.0, pokemonEntity.velocity.y, 0.0)
    }

    private fun findClosestTarget(world: World, origin: BlockPos): BlockPos? {
        val targets = CobbleworkersCacheManager.getTargets(origin, jobType)
        return targets.filter { pos ->
            if (!blockValidator(world, pos)) return@filter false

            val id = Registries.BLOCK.getId(world.getBlockState(pos).block).toString()

            val validTarget = when {
                id.contains("oven") ->
                    hasItemsToCook(world, pos) &&
                            (needsPower(world, pos) || isCooking(world, pos))

                id.contains("accumulator") ->
                    shouldStartChargingAccumulator(world, pos)

                else ->
                    needsPower(world, pos)
            }

            validTarget && !CobbleworkersNavigationUtils.isRecentlyExpired(pos, world)
        }.minByOrNull { it.getSquaredDistance(origin) }
    }


    private fun playElectricSound(world: World, pos: BlockPos) {
        if (world is ServerWorld) world.playSound(null, pos, SoundEvents.ENTITY_GUARDIAN_ATTACK, SoundCategory.BLOCKS, 1.75f, 1.5f)
    }

    private fun spawnElectricParticles(world: World, pokemonEntity: PokemonEntity, pos: BlockPos) {
        if (world !is ServerWorld) return
        val headHeight = try { pokemonEntity.eyeY - pokemonEntity.y } catch (_: Exception) { pokemonEntity.standingEyeHeight.toDouble() }
        val startX = pokemonEntity.x
        val startY = pokemonEntity.y + headHeight
        val startZ = pokemonEntity.z
        val targetCenter = pos.toCenterPos()
        val dx = targetCenter.x - startX
        val dy = (targetCenter.y - 0.5) - startY
        val dz = targetCenter.z - startZ
        val distance = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(0.01)
        val steps = (distance * 6).toInt().coerceIn(6, 18)
        val dirX = dx / distance
        val dirY = dy / distance
        val dirZ = dz / distance

        for (i in 0..steps) {
            val t = i / steps.toDouble()
            var px = startX + dirX * distance * t
            var py = startY + dirY * distance * t
            var pz = startZ + dirZ * distance * t
            val wobble = 0.25 + (distance * 0.05)
            px += (world.random.nextDouble() - 0.5) * wobble
            py += (world.random.nextDouble() - 0.5) * wobble * 0.6
            pz += (world.random.nextDouble() - 0.5) * wobble
            val speed = if (t < 0.2 || t > 0.8) 0.35 + world.random.nextDouble() * 0.25 else 0.2 + world.random.nextDouble() * 0.15
            world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, px, py, pz, 1, 0.0, 0.0, 0.0, speed)
        }
    }

    private fun forceStandPose(pokemonEntity: PokemonEntity) {
        val nbt = pokemonEntity.writeNbt(NbtCompound())
        nbt.putString("PoseType", "STAND")
        pokemonEntity.readNbt(nbt)
    }

    private fun isDesignatedElectrician(pokemonEntity: PokemonEntity): Boolean =
        config.electricians.any { it.lowercase() == pokemonEntity.pokemon.species.translatedName.string.lowercase() }

    override fun isActivelyWorking(pokemon: PokemonEntity): Boolean {
        val uuid = pokemon.pokemon.uuid
        val pos = pokemonPoweringBlocks[uuid] ?: return false
        val id = Registries.BLOCK.getId(pokemon.world.getBlockState(pos).block).toString()
        return if (id.contains("oven")) {
            blockValidator(pokemon.world, pos) && (needsPower(pokemon.world, pos) || isCooking(pokemon.world, pos))
        } else {
            blockValidator(pokemon.world, pos) && needsPower(pokemon.world, pos)
        }
    }

    override fun interrupt(pokemonEntity: PokemonEntity, world: World) {
        try { interruptWork(pokemonEntity, world) } catch (_: Exception) {}
    }
}