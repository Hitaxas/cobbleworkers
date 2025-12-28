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
import accieo.cobbleworkers.sanity.SanityStorage
import accieo.cobbleworkers.pokebed.PokeBedManager
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

        // Cleanup expired bed claims every tick (lightweight operation)
        PokeBedManager.cleanupExpiredClaims(world)
    }

    /**
     * Ticks the action logic for a specific Pokémon.
     * Called ONCE per Pokémon in the pasture per tick.
     */
    fun tickPokemon(world: World, pastureOrigin: BlockPos, pokemonEntity: PokemonEntity) {

        // --- ENSURE SANITY IS LOADED (PERSISTENCE GUARANTEE) ---
        SanityStorage.load(pokemonEntity)

        val owner = pokemonEntity.owner
        if (owner is ServerPlayerEntity) {
            SanityHudSyncServer.tick(owner)
        }

        // === BED SLEEP CHECK (highest priority) ===
        val hasBedClaim = PokeBedManager.getClaimedBed(pokemonEntity.pokemon.uuid) != null
        if (hasBedClaim) {
            // Pokemon is in bed sleep cycle
            workers.forEach { it.interrupt(pokemonEntity, world) }
            SanityManager.tickBedSleep(pokemonEntity, world)
            return
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

        // === NIGHT TIME BED SEEKING ===
        if (SanityManager.shouldUseBed(pokemonEntity, world)) {
            workers.forEach { it.interrupt(pokemonEntity, world) }
            pokemonEntity.navigation.stop()

            val bedPos = PokeBedManager.findNearestBed(world, pokemonEntity.blockPos, pokemonEntity.pokemon.uuid)
            if (bedPos != null && PokeBedManager.claimBed(pokemonEntity.pokemon.uuid, bedPos, world)) {
                SanityManager.forceSleepPose(pokemonEntity)
                // Will navigate to bed on next tick
            }
            return
        }

        // ===== RANDOM SLACK-OFF CHECK (50-30% sanity) =====
        if (SanityManager.shouldSlackOff(pokemonEntity)) {
            handleRecovery(pokemonEntity)
            return
        }

        // ===== NATURAL SLEEP CHECK =====
        val currentPose = pokemonEntity.dataTracker.get(PokemonEntity.POSE_TYPE)
        if (currentPose == com.cobblemon.mod.common.entity.PoseType.SLEEP) {
            // Treat natural sleep EXACTLY like forced rest sleep
            workers.forEach { it.interrupt(pokemonEntity, world) }
            pokemonEntity.navigation.stop()

            SanityManager.recoverWhileSleeping(pokemonEntity)
            SanityStorage.save(pokemonEntity, SanityManager.getSanityPercent(pokemonEntity))

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
            SanityStorage.save(pokemonEntity, SanityManager.getSanityPercent(pokemonEntity))

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
        val currentPose = pokemonEntity.dataTracker.get(PokemonEntity.POSE_TYPE)

        if (currentPose == com.cobblemon.mod.common.entity.PoseType.SLEEP) {
            SanityManager.recoverWhileSleeping(pokemonEntity)
        } else {
            SanityManager.recoverWhileIdle(pokemonEntity)
        }

        SanityStorage.save(pokemonEntity, SanityManager.getSanityPercent(pokemonEntity))
    }

    fun isPokemonWorking(pokemon: PokemonEntity): Boolean {
        return workers
            .filter { it.shouldRun(pokemon) }
            .any { it.isActivelyWorking(pokemon) }
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

    fun getSanityPercent(pokemonEntity: PokemonEntity): Int {
        return SanityManager.getSanityPercent(pokemonEntity)
    }

    fun getStatus(pokemonEntity: PokemonEntity): String {
        return SanityManager.getStatus(pokemonEntity)
    }

    fun releasePokemonFromJobs(pokemon: PokemonEntity) {
        workers.forEach { worker ->
            try {
                worker.interrupt(pokemon, pokemon.world)
            } catch (_: Exception) {}
        }

        // Release any claimed bed
        PokeBedManager.clearPokemon(pokemon.pokemon.uuid)
    }

    fun forceValidators() = jobValidators
}