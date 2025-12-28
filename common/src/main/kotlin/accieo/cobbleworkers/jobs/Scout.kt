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
import accieo.cobbleworkers.utilities.CobbleworkersInventoryUtils
import accieo.cobbleworkers.utilities.CobbleworkersNavigationUtils
import accieo.cobbleworkers.utilities.CobbleworkersTypeUtils
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import net.minecraft.component.DataComponentTypes
import net.minecraft.component.type.MapColorComponent
import net.minecraft.entity.ItemEntity
import net.minecraft.item.FilledMapItem
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.item.map.MapDecorationTypes
import net.minecraft.item.map.MapState
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.registry.entry.RegistryEntry
import net.minecraft.registry.entry.RegistryEntryList
import net.minecraft.server.world.ServerWorld
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import net.minecraft.world.World
import net.minecraft.world.gen.structure.Structure
import java.util.UUID
import kotlin.text.lowercase

object Scout : Worker {
    private val heldItemsByPokemon = mutableMapOf<UUID, List<ItemStack>>()
    private val failedDepositLocations = mutableMapOf<UUID, MutableSet<BlockPos>>()
    private val config get() = CobbleworkersConfigHolder.config.scouts
    private val generalConfig = CobbleworkersConfigHolder.config.general
    private val searchRadius get() = generalConfig.searchRadius
    private val searchHeight get() = generalConfig.searchHeight
    private val lastGenerationTime = mutableMapOf<UUID, Long>()
    private val failedStructureLookups = mutableMapOf<Identifier, Long>()
    private const val FAILED_RETRY_TICKS = 20L * 60L // retry after 60s

    private val LOGGER = org.apache.logging.log4j.LogManager.getLogger("Cobbleworkers-Scout")

    override val jobType: JobType = JobType.Scout
    override val blockValidator: ((World, BlockPos) -> Boolean)? = null


    /**
     * Determines if Pokémon is eligible to be a scout.
     * NOTE: This is used to prevent running the tick method unnecessarily.
     */
    override fun shouldRun(pokemonEntity: PokemonEntity): Boolean {
        if (!config.scoutsEnabled) return false

        return CobbleworkersTypeUtils.isAllowedByType(config.typeScouts, pokemonEntity) || isDesignatedScout(pokemonEntity) || doesPokemonKnowFly(pokemonEntity)
    }

    /**
     * Main logic loop for the scout, executed each tick.
     *
     * NOTE: Origin refers to the pasture's block position.
     */
    override fun tick(world: World, origin: BlockPos, pokemonEntity: PokemonEntity) {
        val pokemonId = pokemonEntity.pokemon.uuid
        val ownerId = pokemonEntity.ownerUuid ?: return
        val heldItems = heldItemsByPokemon[pokemonId]

        val now = world.time
        val lastTime = lastGenerationTime[ownerId] ?: 0L

        if (now - lastTime < config.scoutGenerationCooldownSeconds) {
            return
        }

        lastGenerationTime[ownerId] = now

        if (heldItems.isNullOrEmpty()) {
            failedDepositLocations.remove(pokemonId)
            handleGathering(world, origin, pokemonEntity)
        } else {
            CobbleworkersInventoryUtils.handleDepositing(world, origin, pokemonEntity, heldItems, failedDepositLocations, heldItemsByPokemon)
        }
    }

    /**
     * Finds the closest item on the ground and returns its position and entity.
     */
    private fun findClosestItem(world: World, origin: BlockPos): Pair<BlockPos, ItemEntity>? {
        val searchArea = Box(origin).expand(searchRadius.toDouble(), searchHeight.toDouble(), searchRadius.toDouble())
        val items = world.getEntitiesByClass(ItemEntity::class.java, searchArea) { true }
        return items
            .filter { item -> item.isOnGround && item.stack.item == Items.MAP }
            .minByOrNull { item -> item.squaredDistanceTo(origin.x + 0.5, origin.y + 0.5, origin.z + 0.5) }
            ?.let { it.blockPos to it }
    }

    /**
     * Locate random structure in already generated chunks and create a map to it.
     */
    private fun createStructureMap(world: ServerWorld, origin: BlockPos): ItemStack? {
        val now = world.time
        val structures = CobbleworkersCacheManager.getStructures(world, config.useAllStructures, config.structureTags)

        if (structures.isEmpty()) {
            LOGGER.warn("[Cobbleworkers][Scout] No structure types available for scouting.")
            return null
        }

        val selectedId = structures.random()

        // Negative cache check
        failedStructureLookups[selectedId]?.let { lastFail ->
            if (now - lastFail < FAILED_RETRY_TICKS) {
                LOGGER.debug("[Cobbleworkers][Scout] Skipping $selectedId lookup (cooldown active)")
                return null
            }
        }

        // Positive cache
        val cached = CobbleworkersCacheManager.getCachedStructure(selectedId, now)

        val searchResult = cached ?: locateStructure(world, selectedId, origin)?.also {
            CobbleworkersCacheManager.cacheStructure(selectedId, it, now)
            LOGGER.info("[Cobbleworkers][Scout] Found $selectedId at ${it.first}")
        }

        if (searchResult == null) {
            failedStructureLookups[selectedId] = now
            LOGGER.info("[Cobbleworkers][Scout] No $selectedId nearby. Will retry later.")
            return null
        }

        val structurePos = searchResult.first
        val structureEntry = searchResult.second

        val map = FilledMapItem.createMap(world, structurePos.x, structurePos.z, 2.toByte(), true, true)

        MapState.addDecorationsNbt(
            map,
            structurePos,
            "target",
            MapDecorationTypes.RED_X
        )

        val name = if (config.mapNameIsHidden) {
            Text.of("Scout's Map")
        } else {
            Text.of(cleanMapName(structureEntry.idAsString))
        }

        map.set(DataComponentTypes.CUSTOM_NAME, name)
        map.set(DataComponentTypes.MAP_COLOR, MapColorComponent(0xCC84ED))

        return map
    }


    private fun locateStructure(
        world: ServerWorld,
        structure: Identifier,
        pos: BlockPos
    ): com.mojang.datafixers.util.Pair<BlockPos, RegistryEntry<Structure>>? {

        val structureRegistry = world.server.registryManager.get(RegistryKeys.STRUCTURE)

        val entry = structureRegistry
            .getEntry(RegistryKey.of(RegistryKeys.STRUCTURE, structure))
            .orElse(null)

        if (entry == null) {
            LOGGER.error("[Cobbleworkers][Scout] Structure registry missing: $structure")
            return null
        }

        val entryList = RegistryEntryList.of(entry)

        LOGGER.debug("[Cobbleworkers][Scout] Searching for $structure near $pos...")

        return world.chunkManager.chunkGenerator.locateStructure(
            world,
            entryList,
            pos,
            100,
            false
        ) ?: run {
            LOGGER.debug("[Cobbleworkers][Scout] Structure not found: $structure")
            null
        }
    }


    private fun handleGathering(world: World, origin: BlockPos, pokemonEntity: PokemonEntity) {
        val pokemonId = pokemonEntity.pokemon.uuid
        val (closestItemPos, closestItem) = findClosestItem(world, origin) ?: return

        val currentTarget = CobbleworkersNavigationUtils.getTarget(pokemonId, world)

        if (currentTarget == null) {
            if (!CobbleworkersNavigationUtils.isTargeted(closestItemPos, world) && !CobbleworkersNavigationUtils.isRecentlyExpired(closestItemPos, world)) {
                CobbleworkersNavigationUtils.claimTarget(pokemonId, closestItemPos, world)
            }
            return
        }

        if (currentTarget == closestItemPos) {
            CobbleworkersNavigationUtils.navigateTo(pokemonEntity, closestItemPos)
        }

        if (CobbleworkersNavigationUtils.isPokemonAtPosition(pokemonEntity, currentTarget)) {
            if (closestItem.stack.item == Items.MAP) {
                val singleItem = closestItem.stack.split(1)

                if (closestItem.stack.isEmpty) {
                    closestItem.discard()
                }

                val map = createStructureMap(world as ServerWorld, origin)
                if (map != null) {
                    heldItemsByPokemon[pokemonId] = listOf(map)
                } else {
                    heldItemsByPokemon[pokemonId] = listOf(singleItem)
                }
            }
            CobbleworkersNavigationUtils.releaseTarget(pokemonId, world)
        }
    }

    /**
     * Checks if the Pokémon qualifies as a scout because its species is
     * explicitly listed in the config.
     */
    private fun isDesignatedScout(pokemonEntity: PokemonEntity): Boolean {
        val speciesName = pokemonEntity.pokemon.species.translatedName.string.lowercase()
        return config.scouts.any { it.lowercase() == speciesName }
    }

    /**
     * Checks if the Pokémon qualifies as a scout because of its moves.
     */
    private fun doesPokemonKnowFly(pokemonEntity: PokemonEntity): Boolean {
        return pokemonEntity.pokemon.moveSet.getMoves().any { it.name == "fly" }
    }

    /**
     * Cleans up registry structure names.
     */
    private fun cleanMapName(rawName: String): String {
        return rawName
            .substringAfterLast(":")
            .substringAfterLast("/")
            .replace("_", " ")
            .split(" ")
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    }

    override fun isActivelyWorking(pokemon: PokemonEntity): Boolean {
        val id = pokemon.pokemon.uuid

        if (heldItemsByPokemon.containsKey(id)) return true
        if (CobbleworkersNavigationUtils.getTarget(id, pokemon.world) != null) return true

        return false
    }

    override fun interrupt(pokemon: PokemonEntity, world: World) {
        val id = pokemon.pokemon.uuid
        var hadState = false

        if (heldItemsByPokemon.remove(id) != null) {
            hadState = true
        }

        if (failedDepositLocations.remove(id) != null) {
            hadState = true
        }

        if (CobbleworkersNavigationUtils.getTarget(id, world) != null) {
            CobbleworkersNavigationUtils.releaseTarget(id, world)
            hadState = true
        }

        if (hadState) {
            LOGGER.debug("[Cobbleworkers][Scout] Interrupted and reset state for $id")
        }
    }
}