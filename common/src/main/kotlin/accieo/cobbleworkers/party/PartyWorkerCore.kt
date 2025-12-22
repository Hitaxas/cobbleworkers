/*
 * Copyright (C) 2025 HitaxasTV
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package accieo.cobbleworkers.party

import accieo.cobbleworkers.jobs.WorkerDispatcher
import accieo.cobbleworkers.cache.CobbleworkersCacheManager
import accieo.cobbleworkers.config.CobbleworkersConfigHolder
import accieo.cobbleworkers.enums.JobType
import accieo.cobbleworkers.utilities.CobbleworkersInventoryUtils
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import net.minecraft.util.math.BlockPos
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import net.minecraft.world.World
import net.minecraft.util.math.Box

object PartyWorkerCore {

    private val activePartyPokemon = ConcurrentHashMap.newKeySet<UUID>()
    private val pokemonWorkOrigin = ConcurrentHashMap<UUID, BlockPos>()

    fun markActive(pokemon: PokemonEntity) {
        val uuid = pokemon.pokemon.uuid
        activePartyPokemon.add(uuid)

        val origin = BlockPos.ofFloored(pokemon.x, pokemon.y, pokemon.z)
        pokemonWorkOrigin.putIfAbsent(uuid, origin)

        // 🚀 Instant world awareness
        if (!pokemon.world.isClient) {
            forceImmediateScan(pokemon.world, origin)
        }
    }

    fun markInactive(pokemon: PokemonEntity) {
        val uuid = pokemon.pokemon.uuid

        activePartyPokemon.remove(uuid)
        pokemonWorkOrigin.remove(uuid)

        // 🚨 NEW: force release from any job
        WorkerDispatcher.releasePokemonFromJobs(pokemon)
    }


    fun tickPokemon(pokemon: PokemonEntity) {
        val uuid = pokemon.pokemon.uuid

        if (!activePartyPokemon.contains(uuid)) {
            return
        }


        val world = pokemon.world
        if (world.isClient) {
            return
        }

        val workOrigin = pokemonWorkOrigin[uuid]
            ?: BlockPos.ofFloored(pokemon.x, pokemon.y, pokemon.z)

        // Then tick the pokemon's work logic
        WorkerDispatcher.tickPokemon(world, workOrigin, pokemon)
    }

    fun updateWorkOrigin(pokemon: PokemonEntity, newOrigin: BlockPos) {
        pokemonWorkOrigin[pokemon.pokemon.uuid] = newOrigin
    }

    fun isActive(pokemon: PokemonEntity): Boolean {
        return activePartyPokemon.contains(pokemon.pokemon.uuid)
    }

    fun getActivePokemon(): Set<UUID> {
        return activePartyPokemon.toSet()
    }

    private fun forceImmediateScan(world: World, origin: BlockPos) {
        CobbleworkersCacheManager.removeTargets(origin)

        val radius = CobbleworkersConfigHolder.config.general.searchRadius.toDouble()
        val height = CobbleworkersConfigHolder.config.general.searchHeight.toDouble()

        val box = Box(origin).expand(radius, height, radius)

        BlockPos.stream(box).forEach { pos ->
            val bp = pos.toImmutable()

            val isInventory = CobbleworkersInventoryUtils.blockValidator(world, bp)
            if (isInventory) {
                CobbleworkersCacheManager.addTarget(origin, JobType.Generic, bp)
            }

            WorkerDispatcher.forceValidators().forEach { (jobType, validator) ->
                if (validator(world, bp)) {
                    CobbleworkersCacheManager.addTarget(origin, jobType, bp)
                }
            }
        }
    }



    fun clearAll() {
        activePartyPokemon.clear()
        pokemonWorkOrigin.clear()
    }

}