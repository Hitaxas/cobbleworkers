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
import accieo.cobbleworkers.pokebed.PokeBedManager
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore
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
        WaterGenerator,
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
        TreeFeller,
        Electrician
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
        SanityManager.getSanity(pokemonEntity)

        val owner = pokemonEntity.owner
        if (owner is ServerPlayerEntity) {
            SanityHudSyncServer.tick(owner)
        }

        // 1. BED SLEEP (Active State)
        // If they are currently at or going to a bed, this handles everything.
        val hasBedClaim = PokeBedManager.getClaimedBed(pokemonEntity.pokemon.uuid) != null
        if (hasBedClaim) {
            workers.forEach { it.interrupt(pokemonEntity, world) }
            SanityManager.tickBedSleep(pokemonEntity, world)
            return
        }

        // 2. REFUSAL/BREAK STATE
        // If they are on strike, let SanityManager handle recovery and pose.
        if (SanityManager.isRefusingWork(pokemonEntity)) {
            workers.forEach { it.interrupt(pokemonEntity, world) }
            SanityManager.canWork(pokemonEntity, world)
            return
        }

        // 3. STARTING A FORCED BREAK
        if (SanityManager.needsForcedBreak(pokemonEntity)) {
            workers.forEach { it.interrupt(pokemonEntity, world) }
            pokemonEntity.navigation.stop()
            SanityManager.beginRefusal(pokemonEntity, world)
            return
        }

        // 4. NIGHT TIME CHECK
        // Only triggers if NOT already refusing or in a bed (checked above).
        val pData = pokemonEntity.pokemon
        val isPartyPokemon = pData.storeCoordinates.get()?.store is PlayerPartyStore

        if (!isPartyPokemon && SanityManager.shouldUseBed(pokemonEntity, world)) {
            val bedPos = PokeBedManager.findNearestBed(world, pokemonEntity.blockPos, pData.uuid)
            if (bedPos != null && PokeBedManager.claimBed(pData.uuid, bedPos, world)) {
                workers.forEach { it.interrupt(pokemonEntity, world) }
                pokemonEntity.navigation.stop()
                // SanityManager.forceSleepPose(pokemonEntity) // Optional: Wait until they reach the bed
            }
            return
        }

        // 5. SLACK OFF (Random chance)
        if (SanityManager.shouldSlackOff(pokemonEntity, world)) {
            handleRecovery(pokemonEntity)
            return
        }

        // 6. NATURAL SLEEP (Cobblemon native sleep)
        val currentPose = pokemonEntity.dataTracker.get(PokemonEntity.POSE_TYPE)
        if (currentPose == com.cobblemon.mod.common.entity.PoseType.SLEEP) {
            workers.forEach { it.interrupt(pokemonEntity, world) }
            pokemonEntity.navigation.stop()
            SanityManager.recoverWhileSleeping(pokemonEntity)
            return
        }

        // 7. WORK PHASE
        val eligibleWorkers = workers.filter { it.shouldRun(pokemonEntity) }

        val currentBusyWorker = eligibleWorkers.find { it.isActivelyWorking(pokemonEntity) }

        if (currentBusyWorker != null) {
            currentBusyWorker.tick(world, pastureOrigin, pokemonEntity)
        } else {
            for (worker in eligibleWorkers) {
                worker.tick(world, pastureOrigin, pokemonEntity)
                if (worker.isActivelyWorking(pokemonEntity)) {
                    break
                }
            }
        }

        // 8. FINAL SANITY CHECK
        val activelyWorking = currentBusyWorker != null || eligibleWorkers.any { it.isActivelyWorking(pokemonEntity) }

        if (activelyWorking) {
            SanityManager.drainWhileWorking(pokemonEntity)
            SanityManager.shouldComplain(pokemonEntity, world)
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
        // No manual save needed; recover methods handle persistence
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