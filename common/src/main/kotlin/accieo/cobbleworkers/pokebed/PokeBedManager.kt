/*
 * Copyright (C) 2025 HitaxasTV
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package accieo.cobbleworkers.pokebed

import accieo.cobbleworkers.blocks.PokeBedBlock
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import net.minecraft.util.math.BlockPos
import net.minecraft.world.World
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages PokeBed claiming, navigation, and sleep tracking for worker Pokémon.
 */
object PokeBedManager {

    private data class BedClaim(
        val pokemonId: UUID,
        val bedPos: BlockPos,
        val claimTime: Long,
        val sleepStartTime: Long? = null
    )

    private val bedClaims: MutableMap<UUID, BedClaim> = ConcurrentHashMap()

    private val claimedBeds: MutableMap<BlockPos, UUID> = ConcurrentHashMap()

    private val bedCache: MutableMap<BlockPos, MutableSet<BlockPos>> = ConcurrentHashMap()
    private val cacheExpiry: MutableMap<BlockPos, Long> = ConcurrentHashMap()
    private const val CACHE_DURATION = 20L * 60L // 1 minute
    
    private const val SEARCH_RADIUS = 32
    private const val CLAIM_TIMEOUT = 20L * 60L * 5L // 5 minutes if Pokemon doesn't reach bed

    fun isNightTime(world: World): Boolean {
        val timeOfDay = world.timeOfDay % 24000L
        // Night is from 13000 to 23000 (sunset to sunrise)
        return timeOfDay >= 13000L && timeOfDay < 23000L
    }

    fun findNearestBed(world: World, origin: BlockPos, pokemonId: UUID): BlockPos? {
        // Check cache first
        val cached = getCachedBeds(world, origin)
        if (cached.isNotEmpty()) {
            // Find nearest unclaimed bed from cache
            return cached
                .filter { !isBedClaimed(it) || isClaimedBy(it, pokemonId) }
                .minByOrNull { it.getSquaredDistance(origin) }
        }

        // Scan for beds
        val beds = mutableSetOf<BlockPos>()
        for (x in -SEARCH_RADIUS..SEARCH_RADIUS) {
            for (y in -SEARCH_RADIUS..SEARCH_RADIUS) {
                for (z in -SEARCH_RADIUS..SEARCH_RADIUS) {
                    val pos = origin.add(x, y, z)
                    val block = world.getBlockState(pos).block
                    if (block is PokeBedBlock) {
                        beds.add(pos.toImmutable())
                    }
                }
            }
        }

        updateBedCache(origin, beds, world.time)

        // Find nearest unclaimed
        return beds
            .filter { !isBedClaimed(it) || isClaimedBy(it, pokemonId) }
            .minByOrNull { it.getSquaredDistance(origin) }
    }

    fun claimBed(pokemonId: UUID, bedPos: BlockPos, world: World): Boolean {
        // Check if already claimed by someone else
        if (isBedClaimed(bedPos) && !isClaimedBy(bedPos, pokemonId)) {
            return false
        }

        // Release any previous bed claim
        releaseBed(pokemonId)

        // Claim the new bed
        val claim = BedClaim(
            pokemonId = pokemonId,
            bedPos = bedPos,
            claimTime = world.time
        )
        bedClaims[pokemonId] = claim
        claimedBeds[bedPos] = pokemonId

        return true
    }

    /**
     * Mark Pokemon as sleeping on their claimed bed.
     */
    fun startSleeping(pokemonId: UUID, world: World) {
        val claim = bedClaims[pokemonId] ?: return
        bedClaims[pokemonId] = claim.copy(sleepStartTime = world.time)
    }

    /**
     * Check if Pokemon is currently sleeping on a bed.
     */
    fun isSleepingOnBed(pokemonId: UUID): Boolean {
        return bedClaims[pokemonId]?.sleepStartTime != null
    }

    /**
     * Get the bed position claimed by a Pokemon.
     */
    fun getClaimedBed(pokemonId: UUID): BlockPos? {
        return bedClaims[pokemonId]?.bedPos
    }

    /**
     * Release a bed claim.
     */
    fun releaseBed(pokemonId: UUID) {
        val claim = bedClaims.remove(pokemonId)
        if (claim != null) {
            claimedBeds.remove(claim.bedPos)
        }
    }

    /**
     * Check if a bed is claimed.
     */
    fun isBedClaimed(bedPos: BlockPos): Boolean {
        return claimedBeds.containsKey(bedPos)
    }

    /**
     * Check if a bed is claimed by a specific Pokemon.
     */
    fun isClaimedBy(bedPos: BlockPos, pokemonId: UUID): Boolean {
        return claimedBeds[bedPos] == pokemonId
    }

    /**
     * Check if Pokemon is at their claimed bed position.
     */
    fun isAtBed(pokemon: PokemonEntity): Boolean {
        val bedPos = getClaimedBed(pokemon.pokemon.uuid) ?: return false
        val pokemonPos = pokemon.blockPos
        
        // Check if within 1 block of the bed
        return pokemonPos.getSquaredDistance(bedPos) <= 2.0
    }

    /**
     * Cleanup expired bed claims (Pokemon that never reached the bed).
     */
    fun cleanupExpiredClaims(world: World) {
        val currentTime = world.time
        val expired = bedClaims.entries
            .filter { (_, claim) -> 
                claim.sleepStartTime == null && 
                (currentTime - claim.claimTime) > CLAIM_TIMEOUT 
            }
            .map { it.key }
        
        expired.forEach { releaseBed(it) }
    }

    /**
     * Check if Pokemon should seek a bed (night time or forced rest).
     */
    fun shouldSeekBed(pokemon: PokemonEntity, world: World, forcedRest: Boolean = false): Boolean {
        // Already has a bed
        if (getClaimedBed(pokemon.pokemon.uuid) != null) return false
        
        // Check conditions
        return forcedRest || isNightTime(world)
    }

    /**
     * Navigate Pokemon to their claimed bed.
     */
    fun navigateToBed(pokemon: PokemonEntity): Boolean {
        val bedPos = getClaimedBed(pokemon.pokemon.uuid) ?: return false
        
        // Navigate to the bed
        try {
            val path = pokemon.navigation.findPathTo(bedPos, 1)
            if (path != null) {
                pokemon.navigation.startMovingAlong(path, 1.0)
                return true
            }
        } catch (e: Exception) {
        }
        
        return false
    }

    private fun getCachedBeds(world: World, origin: BlockPos): Set<BlockPos> {
        val expiry = cacheExpiry[origin] ?: 0L
        if (world.time - expiry < CACHE_DURATION) {
            return bedCache[origin] ?: emptySet()
        }
        return emptySet()
    }

    private fun updateBedCache(origin: BlockPos, beds: Set<BlockPos>, time: Long) {
        bedCache[origin] = beds.toMutableSet()
        cacheExpiry[origin] = time
    }

    /**
     * Clear all bed claims (useful when unloading).
     */
    fun clear() {
        bedClaims.clear()
        claimedBeds.clear()
        bedCache.clear()
        cacheExpiry.clear()
    }

    /**
     * Clear bed claim for a specific Pokemon (e.g., when released).
     */
    fun clearPokemon(pokemonId: UUID) {
        releaseBed(pokemonId)
    }
}