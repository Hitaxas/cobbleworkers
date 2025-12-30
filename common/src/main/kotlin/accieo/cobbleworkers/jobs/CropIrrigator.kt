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
import net.minecraft.particle.ParticleTypes
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.sin

object CropIrrigator : Worker {

    private val config = CobbleworkersConfigHolder.config.irrigation
    override val jobType = JobType.CropIrrigator

    private data class IrrigationState(
        var startTime: Long = 0,
        var isIrrigating: Boolean = false,
        var angle: Float = -40f,
        var sweepDir: Int = 1
    )

    private val states = ConcurrentHashMap<UUID, IrrigationState>()

    override val blockValidator = { world: World, pos: BlockPos ->
        world.getBlockState(pos).contains(FarmlandBlock.MOISTURE)
    }

    override fun shouldRun(pokemon: PokemonEntity): Boolean {
        if (!config.cropIrrigatorsEnabled) return false
        return CobbleworkersTypeUtils.isAllowedByType(config.typeIrrigatesCrops, pokemon) ||
                config.cropIrrigators.any {
                    it.equals(pokemon.pokemon.species.translatedName.string, true)
                }
    }

    override fun tick(world: World, origin: BlockPos, pokemon: PokemonEntity) {
        val id = pokemon.pokemon.uuid
        val state = states.computeIfAbsent(id) { IrrigationState() }

        val target = CobbleworkersNavigationUtils.getTarget(id, world)

        // Need a target?
        if (target == null || isHydrated(world, target)) {
            state.isIrrigating = false
            CobbleworkersNavigationUtils.releaseTarget(id, world)

            findDryFarmland(world, origin)?.let {
                CobbleworkersNavigationUtils.claimTarget(id, it, world)
            }
            return
        }

        // Move to target
        if (!CobbleworkersNavigationUtils.isPokemonAtPosition(pokemon, target, 1.5)) {
            state.isIrrigating = false
            CobbleworkersNavigationUtils.navigateTo(pokemon, target)
            return
        }

        // Start irrigation
        if (!state.isIrrigating) {
            state.isIrrigating = true
            state.startTime = world.time
            state.angle = -40f
            state.sweepDir = 1
            pokemon.navigation.stop()
            pokemon.setVelocity(0.0, 0.0, 0.0)
        }

        val elapsed = world.time - state.startTime

        // lock Pokémon in place
        pokemon.velocity = Vec3d.ZERO
        pokemon.navigation.stop()

        // Smooth slow sprinkler sweep
        val sweepAmplitude = 55f          // how wide the arc is
        val sweepPeriod = 120f            // higher = slower movement (~6s per full cycle)

        val t = (world.time - state.startTime).toFloat()
        state.angle = (sin(t / sweepPeriod) * sweepAmplitude)


        val yaw = state.angle + pokemon.bodyYaw

        pokemon.headYaw = yaw
        pokemon.bodyYaw = pokemon.bodyYaw
        pokemon.yaw = pokemon.bodyYaw
        pokemon.pitch = -20f


        if (world is ServerWorld) {
            spawnWaterBeam(world, pokemon)
        }

        if (elapsed % 6L == 0L) {
            hydrateRingGradually(
                world,
                target,
                (elapsed / 20).toInt().coerceAtMost(config.irrigationRadius)
            )
        }

        if (elapsed > 120) {
            irrigateFarmland(world, target, config.irrigationRadius)
            states.remove(id)
            CobbleworkersNavigationUtils.releaseTarget(id, world)
            pokemon.pitch = 0f
        }
    }

    private fun hydrateRingGradually(world: World, center: BlockPos, radius: Int) {
        BlockPos.iterate(center.add(-radius, 0, -radius), center.add(radius, 0, radius)).forEach { pos ->
            val s = world.getBlockState(pos)
            if (s.contains(FarmlandBlock.MOISTURE)) {
                val m = s.get(FarmlandBlock.MOISTURE)
                if (m < FarmlandBlock.MAX_MOISTURE) {
                    world.setBlockState(
                        pos,
                        s.with(FarmlandBlock.MOISTURE, (m + 1).coerceAtMost(7)),
                        Block.NOTIFY_LISTENERS
                    )
                }
            }
        }
    }

    private fun spawnWaterBeam(world: ServerWorld, pokemon: PokemonEntity) {
        val head = pokemon.pos.add(0.0, pokemon.height * 0.85, 0.0)

        val yaw = Math.toRadians(pokemon.headYaw.toDouble())
        val pitch = Math.toRadians(10.0)

        val dx = -sin(yaw) * cos(pitch)
        val dy = -sin(pitch)
        val dz = cos(yaw) * cos(pitch)

        val length = 10
        for (i in 0..length) {
            val d = i * 0.30
            val x = head.x + dx * d
            val y = head.y + dy * d
            val z = head.z + dz * d

            world.spawnParticles(
                ParticleTypes.FALLING_WATER,
                x, y, z,
                1,
                0.02, 0.02, 0.02,
                0.01
            )

            if (i % 3 == 0) {
                world.spawnParticles(
                    ParticleTypes.BUBBLE,
                    x, y, z,
                    1,
                    0.01, 0.01, 0.01,
                    0.05
                )
            }

            if (i == length) {
                world.spawnParticles(
                    ParticleTypes.SPLASH,
                    x, y, z,
                    10,
                    0.3, 0.1, 0.3,
                    0.12
                )
            }
        }
    }


    private fun irrigateFarmland(world: World, center: BlockPos, radius: Int) {
        BlockPos.iterate(center.add(-radius, 0, -radius), center.add(radius, 0, radius)).forEach { pos ->
            val s = world.getBlockState(pos)
            if (s.contains(FarmlandBlock.MOISTURE)) {
                if (s.get(FarmlandBlock.MOISTURE) < FarmlandBlock.MAX_MOISTURE) {
                    world.setBlockState(
                        pos,
                        s.with(FarmlandBlock.MOISTURE, FarmlandBlock.MAX_MOISTURE),
                        Block.NOTIFY_LISTENERS
                    )
                }
            }
        }
    }

    private fun findDryFarmland(world: World, origin: BlockPos): BlockPos? {
        val r = 16
        return BlockPos.iterate(origin.add(-r, -2, -r), origin.add(r, 2, r))
            .firstOrNull { pos ->
                val s = world.getBlockState(pos)
                s.contains(FarmlandBlock.MOISTURE)
                        && s.get(FarmlandBlock.MOISTURE) < FarmlandBlock.MAX_MOISTURE
                        && !CobbleworkersNavigationUtils.isTargeted(pos, world)
            }?.toImmutable()
    }

    private fun isHydrated(world: World, pos: BlockPos): Boolean {
        val s = world.getBlockState(pos)
        return !s.contains(FarmlandBlock.MOISTURE)
                || s.get(FarmlandBlock.MOISTURE) >= FarmlandBlock.MAX_MOISTURE
    }

    override fun isActivelyWorking(pokemon: PokemonEntity): Boolean {
        val id = pokemon.pokemon.uuid
        val state = states[id] ?: return false

        // ONLY busy if actively in the "spraying" state
        return state.isIrrigating
    }


    override fun interrupt(pokemon: PokemonEntity, world: World) {
        states.remove(pokemon.pokemon.uuid)
        CobbleworkersNavigationUtils.releaseTarget(pokemon.pokemon.uuid, world)
    }
}
