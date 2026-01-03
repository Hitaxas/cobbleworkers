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
    private val pokemonPoweringOvens = ConcurrentHashMap<UUID, BlockPos>()
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
        id.contains("cookingforblockheads") && id.contains("oven")
    }

    override fun shouldRun(pokemonEntity: PokemonEntity): Boolean {
        if (!accieo.cobbleworkers.utilities.CobbleworkersWorkToggle.canWork(pokemonEntity.pokemon)) {
            return false
        }
        if (!config.electriciansEnabled) return false
        return CobbleworkersTypeUtils.isAllowedByType(config.typePowersOvens, pokemonEntity) ||
                isDesignatedElectrician(pokemonEntity)
    }

    override fun tick(world: World, origin: BlockPos, pokemonEntity: PokemonEntity) {
        handleElectricalPowering(world, origin, pokemonEntity)
    }

    private fun interruptWork(pokemonEntity: PokemonEntity, world: World) {
        val uuid = pokemonEntity.pokemon.uuid
        val tendingPos = pokemonPoweringOvens[uuid]
        if (tendingPos != null) {
            lastCookingSpeedBoost.remove(tendingPos)
        }
        pokemonPoweringOvens.remove(uuid)
        lastSoundTime.remove(uuid)
        CobbleworkersNavigationUtils.releaseTarget(uuid, world)
    }

    private fun isLegendaryOrMythical(pokemonEntity: PokemonEntity): Boolean {
        val labels = pokemonEntity.pokemon.species.labels
        return labels.contains("legendary") || labels.contains("mythical")
    }

    private fun handleElectricalPowering(world: World, origin: BlockPos, pokemonEntity: PokemonEntity) {
        val pokemonId = pokemonEntity.pokemon.uuid
        val now = world.time
        val poweringPos = pokemonPoweringOvens[pokemonId]

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

            val stillNeedsPower = needsPower(world, poweringPos)
            val stillCooking = isCooking(world, poweringPos)

            if (!stillNeedsPower && !stillCooking) {
                interruptWork(pokemonEntity, world)
                return
            }

            pokemonEntity.navigation.stop()
            lookAtOven(pokemonEntity, poweringPos)

            if (isLegendaryOrMythical(pokemonEntity) && stillCooking) {
                val lastBoost = lastCookingSpeedBoost[poweringPos] ?: 0L
                if (now - lastBoost >= COOKING_SPEED_BOOST_INTERVAL) {
                    addEnergyToOven(world, poweringPos, true)
                    lastCookingSpeedBoost[poweringPos] = now
                }
            }

            val lastPower = lastPowerTime[pokemonId] ?: 0L
            val isLegendary = isLegendaryOrMythical(pokemonEntity)
            val effectiveCooldown = if (isLegendary) cooldownTicks / 2 else cooldownTicks

            if (stillNeedsPower && now - lastPower >= effectiveCooldown) {
                addEnergyToOven(world, poweringPos, false)
                lastPowerTime[pokemonId] = now
            }

            val lastSound = lastSoundTime[pokemonId] ?: 0L
            if (now - lastSound >= SOUND_INTERVAL) {
                playElectricSound(world, poweringPos)
                lastSoundTime[pokemonId] = now
            }
            spawnElectricParticles(world, pokemonEntity, poweringPos)
            return
        }

        val lastTime = lastPowerTime[pokemonId] ?: 0L
        val isLegendary = isLegendaryOrMythical(pokemonEntity)
        val searchCooldown = if (isLegendary) cooldownTicks / 2 else cooldownTicks
        if (now - lastTime < searchCooldown) return

        val closestOven = findClosestOven(world, origin) ?: return
        val currentTarget = CobbleworkersNavigationUtils.getTarget(pokemonId, world)

        if (currentTarget == null) {
            if (!CobbleworkersNavigationUtils.isTargeted(closestOven, world) &&
                !CobbleworkersNavigationUtils.isRecentlyExpired(closestOven, world) &&
                !pokemonPoweringOvens.values.contains(closestOven)) {
                CobbleworkersNavigationUtils.claimTarget(pokemonId, closestOven, world)
            }
            return
        }

        if (currentTarget == closestOven) {
            CobbleworkersNavigationUtils.navigateTo(pokemonEntity, closestOven)
        }

        if (CobbleworkersNavigationUtils.isPokemonAtPosition(pokemonEntity, closestOven)) {
            if (hasItemsToCook(world, closestOven) && (needsPower(world, closestOven) || isCooking(world, closestOven))) {
                pokemonPoweringOvens[pokemonId] = closestOven
                lastSoundTime[pokemonId] = now
                positionPokemonAtOven(world, closestOven, pokemonEntity)
                playElectricSound(world, closestOven)
            } else {
                CobbleworkersNavigationUtils.releaseTarget(pokemonId, world)
            }
        }
    }

    private fun lookAtOven(pokemonEntity: PokemonEntity, pos: BlockPos) {
        val ovenCenter = pos.toCenterPos()
        val dx = ovenCenter.x - pokemonEntity.x
        val dz = ovenCenter.z - pokemonEntity.z
        val yaw = (Math.atan2(dz, dx) * 180.0 / Math.PI).toFloat() - 90.0f
        pokemonEntity.headYaw = yaw
        pokemonEntity.bodyYaw = yaw
        pokemonEntity.yaw = yaw
    }

    private fun findClosestOven(world: World, origin: BlockPos): BlockPos? {
        val possibleTargets = CobbleworkersCacheManager.getTargets(origin, jobType)
        if (possibleTargets.isEmpty()) return null

        return possibleTargets
            .filter { pos ->
                blockValidator(world, pos) && hasItemsToCook(world, pos) &&
                        (needsPower(world, pos) || isCooking(world, pos)) && hasHeatingUnit(world, pos) &&
                        !CobbleworkersNavigationUtils.isRecentlyExpired(pos, world)
            }
            .minByOrNull { it.getSquaredDistance(origin) }
    }

    private fun hasHeatingUnit(world: World, pos: BlockPos): Boolean {
        val blockEntity = world.getBlockEntity(pos) ?: return false
        val nbt = blockEntity.createNbt(world.registryManager)
        return nbt.getBoolean("HasPowerUpgrade")
    }

    private fun needsPower(world: World, pos: BlockPos): Boolean {
        val blockEntity = world.getBlockEntity(pos) ?: return false
        val nbt = blockEntity.createNbt(world.registryManager)
        return nbt.getBoolean("HasPowerUpgrade") && nbt.getInt("EnergyStored") < 8000
    }

    private fun isCooking(world: World, pos: BlockPos): Boolean {
        val blockEntity = world.getBlockEntity(pos) ?: return false
        val nbt = blockEntity.createNbt(world.registryManager)
        if (nbt.contains("CookTimes")) {
            val cookTimes = nbt.getIntArray("CookTimes")
            return cookTimes.any { it > 0 } // Active progress found
        }
        return false
    }

    private fun hasItemsToCook(world: World, pos: BlockPos): Boolean {
        val blockEntity = world.getBlockEntity(pos) ?: return false
        val nbt = blockEntity.createNbt(world.registryManager)
        if (nbt.contains("ItemHandler")) {
            val items = nbt.getCompound("ItemHandler").getList("Items", 10)
            for (i in 0 until items.size) {
                val slot = items.getCompound(i).getByte("Slot").toInt()
                if (slot in 0..2 || slot in 7..15) return true
            }
        }
        return false
    }

    private fun addEnergyToOven(world: World, ovenPos: BlockPos, applyBoost: Boolean) {
        val blockEntity = world.getBlockEntity(ovenPos) ?: return
        val registryLookup = world.registryManager
        val nbt = blockEntity.createNbt(registryLookup)

        val currentEnergy = nbt.getInt("EnergyStored")
        nbt.putInt("EnergyStored", (currentEnergy + config.fePerCharge).coerceAtMost(10000))

        if (applyBoost && nbt.contains("CookTimes")) {
            val cookTimes = nbt.getIntArray("CookTimes")
            val maxCookTimes = if (nbt.contains("MaxCookTimes")) nbt.getIntArray("MaxCookTimes") else IntArray(cookTimes.size) { 200 }
            var modified = false

            for (i in cookTimes.indices) {
                val current = cookTimes[i]
                val max = if (i < maxCookTimes.size) maxCookTimes[i] else 200
                if (current in 1 until max - 3) {
                    cookTimes[i] = (current + 5).coerceAtMost(max - 2) // Mirrored safer logic
                    modified = true
                }
            }
            if (modified) nbt.putIntArray("CookTimes", cookTimes)
        }

        try {
            blockEntity.read(nbt, registryLookup)
        } catch (ex: Exception) {
            try {
                val readNbtMethod = blockEntity.javaClass.getMethod("readNbt", NbtCompound::class.java)
                readNbtMethod.invoke(blockEntity, nbt)
            } catch (_: Exception) {}
        }

        blockEntity.markDirty()
        val state = world.getBlockState(ovenPos)
        try {
            state.entries.keys.find { p -> p.name == "active" && p is BooleanProperty }?.let { prop ->
                world.setBlockState(ovenPos, state.with(prop as BooleanProperty, true), 3)
            }
        } catch (_: Exception) {}
        world.updateListeners(ovenPos, state, state, 3)
    }

    private fun playElectricSound(world: World, pos: BlockPos) {
        if (world is ServerWorld) {
            world.playSound(null, pos, SoundEvents.ENTITY_GUARDIAN_ATTACK, SoundCategory.BLOCKS, 1.75f, 1.5f)
        }
    }

    private fun spawnElectricParticles(world: World, pokemonEntity: PokemonEntity, ovenPos: BlockPos) {
        if (world !is ServerWorld) return

        val headHeight = try {
            pokemonEntity.eyeY - pokemonEntity.y
        } catch (_: Exception) {
            pokemonEntity.standingEyeHeight.toDouble()
        }

        val startX = pokemonEntity.x
        val startY = pokemonEntity.y + headHeight
        val startZ = pokemonEntity.z

        val ovenCenter = ovenPos.toCenterPos()
        val dx = ovenCenter.x - startX
        val dy = (ovenCenter.y - 0.5) - startY
        val dz = ovenCenter.z - startZ

        val distance = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(0.01)
        val steps = (distance * 6).toInt().coerceIn(6, 18)   // More distance = slightly denser arc

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

            val speed =
                if (t < 0.2 || t > 0.8)
                    0.35 + world.random.nextDouble() * 0.25
                else
                    0.2 + world.random.nextDouble() * 0.15

            world.spawnParticles(
                ParticleTypes.ELECTRIC_SPARK,
                px,
                py,
                pz,
                1,
                0.0, 0.0, 0.0,
                speed
            )
        }

        if (world.random.nextFloat() < 0.45f) {
            world.spawnParticles(
                ParticleTypes.ELECTRIC_SPARK,
                ovenCenter.x,
                ovenCenter.y,
                ovenCenter.z,
                3,
                0.25, 0.25, 0.25,
                0.05
            )
        }

        if (world.random.nextFloat() < 0.35f) {
            world.spawnParticles(
                ParticleTypes.ELECTRIC_SPARK,
                startX,
                startY,
                startZ,
                4,
                0.15, 0.25, 0.15,
                0.1
            )
        }
    }

    private fun positionPokemonAtOven(world: World, ovenPos: BlockPos, pokemonEntity: PokemonEntity) {
        val state = world.getBlockState(ovenPos)
        val facing = try {
            state.entries.keys.find { it.name == "facing" }?.let { state.get(it) as? net.minecraft.util.math.Direction }
        } catch (_: Exception) { null } ?: net.minecraft.util.math.Direction.NORTH

        val offset = facing.vector
        val targetCenter = ovenPos.add(offset.x * 2, 0, offset.z * 2).toCenterPos()

        pokemonEntity.refreshPositionAndAngles(targetCenter.x, pokemonEntity.y, targetCenter.z, pokemonEntity.yaw, pokemonEntity.pitch)
        lookAtOven(pokemonEntity, ovenPos)
        pokemonEntity.navigation.stop()
        pokemonEntity.setVelocity(0.0, pokemonEntity.velocity.y, 0.0)
    }

    private fun forceStandPose(pokemonEntity: PokemonEntity) {
        val nbt = pokemonEntity.writeNbt(NbtCompound())
        nbt.putString("PoseType", "STAND")
        pokemonEntity.readNbt(nbt)
    }

    private fun isDesignatedElectrician(pokemonEntity: PokemonEntity): Boolean {
        return config.electricians.any { it.lowercase() == pokemonEntity.pokemon.species.translatedName.string.lowercase() }
    }

    override fun isActivelyWorking(pokemon: PokemonEntity): Boolean {
        val uuid = pokemon.pokemon.uuid
        val pos = pokemonPoweringOvens[uuid] ?: return false
        return blockValidator(pokemon.world, pos) && (needsPower(pokemon.world, pos) || isCooking(pokemon.world, pos))
    }

    override fun interrupt(pokemonEntity: PokemonEntity, world: World) {
        try { interruptWork(pokemonEntity, world) } catch (_: Exception) {}
    }
}