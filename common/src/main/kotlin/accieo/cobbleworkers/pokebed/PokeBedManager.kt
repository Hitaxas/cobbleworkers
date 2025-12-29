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

    // Cache management
    private val bedCache: MutableMap<BlockPos, MutableSet<BlockPos>> = ConcurrentHashMap()
    private val cacheExpiry: MutableMap<BlockPos, Long> = ConcurrentHashMap()
    private const val CACHE_DURATION = 20L * 60L // 1 minute

    private const val SEARCH_RADIUS = 32
    private const val CLAIM_TIMEOUT = 20L * 60L * 2L // Reduced to 2 minutes for better responsiveness

    fun isNightTime(world: World): Boolean {
        val timeOfDay = world.timeOfDay % 24000L
        return timeOfDay >= 13000L && timeOfDay < 23000L
    }

    fun findNearestBed(world: World, origin: BlockPos, pokemonId: UUID): BlockPos? {
        val cached = getCachedBeds(world, origin)
        if (cached.isNotEmpty()) {
            return cached
                .filter { !isBedClaimed(it) || isClaimedBy(it, pokemonId) }
                // Use squared distance for performance
                .minByOrNull { it.getSquaredDistance(origin) }
        }

        val beds = mutableSetOf<BlockPos>()
        for (x in -SEARCH_RADIUS..SEARCH_RADIUS) {
            for (z in -SEARCH_RADIUS..SEARCH_RADIUS) {
                for (y in -8..8) {
                    val pos = origin.add(x, y, z)
                    if (world.isAir(pos)) continue

                    val block = world.getBlockState(pos).block
                    if (block is PokeBedBlock) {
                        beds.add(pos.toImmutable())
                    }
                }
            }
        }

        updateBedCache(origin, beds, world.time)

        return beds
            .filter { !isBedClaimed(it) || isClaimedBy(it, pokemonId) }
            .minByOrNull { it.getSquaredDistance(origin) }
    }

    fun claimBed(pokemonId: UUID, bedPos: BlockPos, world: World): Boolean {
        if (isBedClaimed(bedPos) && !isClaimedBy(bedPos, pokemonId)) {
            return false
        }

        releaseBed(pokemonId)

        val claim = BedClaim(
            pokemonId = pokemonId,
            bedPos = bedPos,
            claimTime = world.time
        )
        bedClaims[pokemonId] = claim
        claimedBeds[bedPos] = pokemonId

        return true
    }

    fun startSleeping(pokemonId: UUID, world: World) {
        val claim = bedClaims[pokemonId] ?: return
        bedClaims[pokemonId] = claim.copy(sleepStartTime = world.time)
    }

    fun isSleepingOnBed(pokemonId: UUID): Boolean {
        return bedClaims[pokemonId]?.sleepStartTime != null
    }

    fun getClaimedBed(pokemonId: UUID): BlockPos? {
        return bedClaims[pokemonId]?.bedPos
    }

    fun releaseBed(pokemonId: UUID) {
        val claim = bedClaims.remove(pokemonId)
        if (claim != null) {
            claimedBeds.remove(claim.bedPos)
        }
    }

    fun isBedClaimed(bedPos: BlockPos): Boolean {
        return claimedBeds.containsKey(bedPos)
    }

    fun isClaimedBy(bedPos: BlockPos, pokemonId: UUID): Boolean {
        return claimedBeds[bedPos] == pokemonId
    }

    /**
     * More robust check for "at bed" status.
     */
    fun isAtBed(pokemon: PokemonEntity): Boolean {
        val bedPos = getClaimedBed(pokemon.pokemon.uuid) ?: return false
        val pokemonPos = pokemon.pos

        val dx = pokemonPos.x - (bedPos.x + 0.5)
        val dz = pokemonPos.z - (bedPos.z + 0.5)
        val dy = pokemonPos.y - bedPos.y

        return (dx * dx + dz * dz) < 1.2 && Math.abs(dy) < 1.0
    }

    fun cleanupExpiredClaims(world: World) {
        if (world.time % 100 != 0L) return // Only run every 5 seconds for performance

        val currentTime = world.time
        val expired = bedClaims.entries
            .filter { (_, claim) ->
                claim.sleepStartTime == null &&
                        (currentTime - claim.claimTime) > CLAIM_TIMEOUT
            }
            .map { it.key }

        expired.forEach { releaseBed(it) }

        if (world.time % 1200 == 0L) {
            val toRemove = cacheExpiry.filter { currentTime - it.value > CACHE_DURATION }.keys
            toRemove.forEach {
                bedCache.remove(it)
                cacheExpiry.remove(it)
            }
        }
    }

    fun navigateToBed(pokemon: PokemonEntity): Boolean {
        val bedPos = getClaimedBed(pokemon.pokemon.uuid) ?: return false

        if (isAtBed(pokemon)) {
            pokemon.navigation.stop()
            return true
        }

        try {
            val target = bedPos.toCenterPos()
            val path = pokemon.navigation.findPathTo(target.x, target.y, target.z, 0)

            if (path != null) {
                pokemon.navigation.startMovingAlong(path, 1.2) // Slightly faster to go to bed
                return true
            } else {
                pokemon.moveControl.moveTo(target.x, target.y, target.z, 1.2)
            }
        } catch (_: Exception) {}

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

    fun clear() {
        bedClaims.clear()
        claimedBeds.clear()
        bedCache.clear()
        cacheExpiry.clear()
    }

    fun clearPokemon(pokemonId: UUID) {
        releaseBed(pokemonId)
    }
}