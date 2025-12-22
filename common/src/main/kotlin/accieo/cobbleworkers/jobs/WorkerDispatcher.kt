/*
 * Copyright (C) 2025 Accieo
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package accieo.cobbleworkers.jobs

import accieo.cobbleworkers.enums.JobType
import accieo.cobbleworkers.interfaces.Worker
import accieo.cobbleworkers.utilities.DeferredBlockScanner
import accieo.cobbleworkers.sanity.SanityManager
import accieo.cobbleworkers.sanity.SanityHudSyncServer
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import net.minecraft.util.math.BlockPos
import net.minecraft.world.World
import java.util.UUID
import net.minecraft.server.network.ServerPlayerEntity

object WorkerDispatcher {

    /**
     * Worker registry.
     */
    private val workers: List<Worker> = listOf(
        ApricornHarvester,
        AmethystHarvester,
        Archeologist,
        BerryHarvester,
        BrewingStandFuelGenerator,
        CropHarvester,
        CropIrrigator,
        DiveLooter,
        FireExtinguisher,
        FishingLootGenerator,
        FuelGenerator,
        GroundItemGatherer,
        Healer,
        HoneyCollector,
        LavaGenerator,
        MintHarvester,
        NetherwartHarvester,
        PickUpLooter,
        Scout,
        SnowGenerator,
        TumblestoneHarvester,
        WaterGenerator,
    )

    /**
     * Gathers all block validators from registered workers.
     */
    private val jobValidators: Map<JobType, (World, BlockPos) -> Boolean> = workers
        .mapNotNull { worker -> worker.blockValidator?.let { worker.jobType to it } }
        .toMap()

    /**
     * Ticks the deferred block scanning for a single pasture.
     * Called ONCE per pasture per tick.
     */
    fun tickAreaScan(world: World, pastureOrigin: BlockPos) {
        DeferredBlockScanner.tickPastureAreaScan(
            world,
            pastureOrigin,
            jobValidators
        )
    }

    /**
     * Ticks the action logic for a specific Pokémon.
     * Called ONCE per Pokémon in the pasture per tick.
     */
    fun tickPokemon(world: World, pastureOrigin: BlockPos, pokemonEntity: PokemonEntity) {

        val owner = pokemonEntity.owner
        if (owner is ServerPlayerEntity) {
            SanityHudSyncServer.tick(owner)
        }

        // ===== REFUSAL STATE (Below 50% sanity) =====
        if (SanityManager.isRefusingWork(pokemonEntity)) {
            SanityManager.canWork(pokemonEntity, world)
            return
        }

        // ===== FORCED BREAK CHECK =====
        if (SanityManager.needsForcedBreak(pokemonEntity)) {
            workers.forEach { it.interrupt(pokemonEntity, world) }
            pokemonEntity.navigation.stop()
            SanityManager.beginRefusal(pokemonEntity, world)
            return
        }

        // ===== RANDOM SLACK-OFF CHECK (50-30% sanity) =====
        if (SanityManager.shouldSlackOff(pokemonEntity)) {
            handleRecovery(pokemonEntity)
            return
        }

        // ===== WORK PHASE =====
        val eligibleWorkers = workers.filter { it.shouldRun(pokemonEntity) }

        eligibleWorkers.forEach { worker ->
            worker.tick(world, pastureOrigin, pokemonEntity)
        }

        // ===== SANITY MANAGEMENT =====
        val activelyWorking = workers
            .filter { it.shouldRun(pokemonEntity) }
            .any { it.isActivelyWorking(pokemonEntity) }


        if (activelyWorking) {
            SanityManager.drainWhileWorking(pokemonEntity)

            if (SanityManager.shouldComplain(pokemonEntity, world)) {
                // optional effects here
            }
        } else {
            handleRecovery(pokemonEntity)
        }
    }

    /**
     * Handles sanity recovery based on Pokemon's current state.
     * Sleeping recovers 3.5x faster than idle.
     */
    private fun handleRecovery(pokemonEntity: PokemonEntity) {
        // Check if Pokemon is sleeping
        val currentPose = pokemonEntity.dataTracker.get(PokemonEntity.POSE_TYPE)

        if (currentPose == com.cobblemon.mod.common.entity.PoseType.SLEEP) {
            SanityManager.recoverWhileSleeping(pokemonEntity)
        } else {
            SanityManager.recoverWhileIdle(pokemonEntity)
        }
    }

    /**
     * Forces Pokemon to wake up if they're actively working.
     * Used to prevent sleeping during critical work tasks.
     */
    fun forceAwakeIfWorking(pokemonEntity: PokemonEntity) {
        val pokemonUuid: UUID = pokemonEntity.pokemon.uuid

        val isWorking = workers.any { worker ->
            when (worker) {
                is FuelGenerator -> worker.isPokemonTending(pokemonUuid)
                // Add other workers that require Pokemon to stay awake
                else -> false
            }
        }

        if (isWorking) {
            pokemonEntity.wakeUp()
        }
    }

    fun forceImmediateScan(world: World, origin: BlockPos) {
        DeferredBlockScanner.tickPastureAreaScan(world, origin, jobValidators, true)
    }

    /**
     * Gets the current sanity percentage for a Pokemon.
     * Useful for UI display or debugging.
     */
    fun getSanityPercent(pokemonEntity: PokemonEntity): Int {
        return SanityManager.getSanityPercent(pokemonEntity)
    }

    /**
     * Gets a readable status string for a Pokemon.
     * Useful for debugging or UI tooltips.
     */
    fun getStatus(pokemonEntity: PokemonEntity): String {
        return SanityManager.getStatus(pokemonEntity)
    }

    fun releasePokemonFromJobs(pokemon: PokemonEntity) {
        workers.forEach { worker ->
            try {
                worker.interrupt(pokemon, pokemon.world)
            } catch (_: Exception) {}
        }
    }


    fun forceValidators() = jobValidators
}