/*
 * Copyright (C) 2025 HitaxasTV
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package accieo.cobbleworkers.jobs

import accieo.cobbleworkers.config.CobbleworkersConfigHolder
import accieo.cobbleworkers.enums.JobType
import accieo.cobbleworkers.interfaces.Worker
import accieo.cobbleworkers.utilities.CobbleworkersInventoryUtils
import accieo.cobbleworkers.utilities.CobbleworkersNavigationUtils
import accieo.cobbleworkers.utilities.CobbleworkersTypeUtils
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import net.minecraft.block.*
import net.minecraft.item.BlockItem
import net.minecraft.item.ItemStack
import net.minecraft.particle.BlockStateParticleEffect
import net.minecraft.particle.ParticleTypes
import net.minecraft.registry.tag.BlockTags
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvents
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object TreeFeller : Worker {

    private val config = CobbleworkersConfigHolder.config.treefellers
    private val heldItemsByPokemon = ConcurrentHashMap<UUID, List<ItemStack>>()
    private val failedDepositLocations = ConcurrentHashMap<UUID, MutableSet<BlockPos>>()
    private val pokemonFellingTrees = ConcurrentHashMap<UUID, TreeFellingState>()

    private const val REQUIRED_CHOP_TICKS = 80L

    private data class TreeFellingState(
        val treeBase: BlockPos,
        val blocksToChop: MutableList<BlockPos>,
        val leafPositions: Set<BlockPos>,
        var chopProgress: Long = 0,
        var isReplanting: Boolean = false
    )

    override val jobType: JobType = JobType.TreeFeller

    override val blockValidator: ((World, BlockPos) -> Boolean) = { world: World, pos: BlockPos ->
        val state = world.getBlockState(pos)
        isLogBlock(state.block) && isTreeBase(world, pos) && hasLeavesNearby(world, pos)
    }

    override fun shouldRun(pokemonEntity: PokemonEntity): Boolean {
        if (!config.treeFellersEnabled) return false
        return CobbleworkersTypeUtils.isAllowedByType(config.typeFellsTrees, pokemonEntity) ||
                isDesignatedFeller(pokemonEntity)
    }

    override fun tick(world: World, origin: BlockPos, pokemonEntity: PokemonEntity) {
        val pokemonId = pokemonEntity.pokemon.uuid
        val heldItems = heldItemsByPokemon[pokemonId] ?: emptyList()
        val fellingState = pokemonFellingTrees[pokemonId]

        if (fellingState != null || (heldItems.isEmpty() && !failedDepositLocations.containsKey(pokemonId))) {
            handleTreeFelling(world, origin, pokemonEntity)
        } else {
            CobbleworkersInventoryUtils.handleDepositing(world, origin, pokemonEntity, heldItems, failedDepositLocations, heldItemsByPokemon)
        }
    }

    private fun handleTreeFelling(world: World, origin: BlockPos, pokemonEntity: PokemonEntity) {
        val pokemonId = pokemonEntity.pokemon.uuid
        val fellingState = pokemonFellingTrees[pokemonId]

        if (fellingState != null) {
            if (!CobbleworkersNavigationUtils.isPokemonAtPosition(pokemonEntity, fellingState.treeBase, 3.5)) {
                CobbleworkersNavigationUtils.navigateTo(pokemonEntity, fellingState.treeBase)
                return
            }

            pokemonEntity.navigation.stop()
            pokemonEntity.velocity = Vec3d.ZERO

            if (fellingState.isReplanting) {
                executeReplant(world, pokemonEntity, fellingState)
                return
            }

            lookAt(pokemonEntity, fellingState.treeBase)
            fellingState.chopProgress++

            if (fellingState.chopProgress % 3 == 0L) {
                spawnChopEffects(world, fellingState.treeBase)
            }

            if (fellingState.chopProgress >= REQUIRED_CHOP_TICKS) {
                fellingState.blocksToChop.forEach { pos ->
                    if (!world.getBlockState(pos).isAir) {
                        collectAndBreak(world, pos, pokemonEntity)
                    }
                }

                fellingState.isReplanting = true
                if (world is ServerWorld) {
                    world.playSound(null, fellingState.treeBase, SoundEvents.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, SoundCategory.BLOCKS, 1.0f, 0.8f)
                }
            }
            return
        }

        val currentTarget = CobbleworkersNavigationUtils.getTarget(pokemonId, world)
        if (currentTarget == null) {
            val tree = findNearestTree(world, origin)
            if (tree != null) CobbleworkersNavigationUtils.claimTarget(pokemonId, tree, world)
            return
        }

        if (CobbleworkersNavigationUtils.isPokemonAtPosition(pokemonEntity, currentTarget, 3.5)) {
            val blocks = scanTree(world, currentTarget)
            if (blocks.isNotEmpty()) {
                val leafPositions = blocks.filter { pos ->
                    isLeavesBlock(world.getBlockState(pos).block)
                }.toSet()

                leafPositions.forEach { pos ->
                    val state = world.getBlockState(pos)
                    if (state.block is LeavesBlock && state.contains(LeavesBlock.PERSISTENT)) {
                        world.setBlockState(pos, state.with(LeavesBlock.PERSISTENT, true), Block.NOTIFY_LISTENERS)
                    }
                }

                pokemonFellingTrees[pokemonId] = TreeFellingState(
                    treeBase = currentTarget,
                    blocksToChop = blocks.toMutableList(),
                    leafPositions = leafPositions
                )
            } else {
                CobbleworkersNavigationUtils.releaseTarget(pokemonId, world)
            }
        } else {
            CobbleworkersNavigationUtils.navigateTo(pokemonEntity, currentTarget)
        }
    }

    private fun collectAndBreak(world: World, pos: BlockPos, pokemonEntity: PokemonEntity) {
        val state = world.getBlockState(pos)
        if (world is ServerWorld) {
            val drops = Block.getDroppedStacks(state, world, pos, null, pokemonEntity, ItemStack.EMPTY)
            val uuid = pokemonEntity.pokemon.uuid
            val currentItems = heldItemsByPokemon[uuid]?.toMutableList() ?: mutableListOf()
            drops.forEach { currentItems.add(it.copy()) }
            heldItemsByPokemon[uuid] = currentItems
        }
        world.breakBlock(pos, false)
    }

    private fun executeReplant(world: World, pokemonEntity: PokemonEntity, state: TreeFellingState) {
        val uuid = pokemonEntity.pokemon.uuid
        val items = heldItemsByPokemon[uuid]?.toMutableList() ?: mutableListOf()

        val saplingIdx = items.indexOfFirst { it.item is BlockItem && (it.item as BlockItem).block is SaplingBlock }

        if (saplingIdx != -1) {
            val stack = items[saplingIdx]
            val sapling = (stack.item as BlockItem).block
            world.setBlockState(state.treeBase, sapling.defaultState)
            world.playSound(null, state.treeBase, SoundEvents.BLOCK_GRASS_PLACE, SoundCategory.BLOCKS, 1f, 1f)

            stack.decrement(1)
            if (stack.isEmpty) items.removeAt(saplingIdx)
            heldItemsByPokemon[uuid] = items
        }

        pokemonFellingTrees.remove(uuid)
        CobbleworkersNavigationUtils.releaseTarget(uuid, world)
    }

    private fun lookAt(pokemon: PokemonEntity, pos: BlockPos) {
        val dx = pos.x + 0.5 - pokemon.x
        val dz = pos.z + 0.5 - pokemon.z
        val yaw = (Math.toDegrees(Math.atan2(-dx, dz))).toFloat()
        pokemon.headYaw = yaw
        pokemon.bodyYaw = yaw
        pokemon.yaw = yaw
    }

    private fun spawnChopEffects(world: World, pos: BlockPos) {
        if (world !is ServerWorld) return
        val state = world.getBlockState(pos)
        world.spawnParticles(BlockStateParticleEffect(ParticleTypes.BLOCK, state), pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, 6, 0.2, 0.2, 0.2, 0.1)
        world.playSound(null, pos, SoundEvents.ENTITY_PLAYER_ATTACK_STRONG, SoundCategory.BLOCKS, 0.5f, 0.9f)
    }

    private fun hasLeavesNearby(world: World, logPos: BlockPos): Boolean {
        val searchRadius = config.leavesSearchRadius
        for (dx in -searchRadius..searchRadius) {
            for (dy in -searchRadius..searchRadius) {
                for (dz in -searchRadius..searchRadius) {
                    val checkPos = logPos.add(dx, dy, dz)
                    val state = world.getBlockState(checkPos)
                    if (isLeavesBlock(state.block)) {
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun scanTree(world: World, startPos: BlockPos): List<BlockPos> {
        val logs = mutableListOf<BlockPos>()
        val leaves = mutableListOf<BlockPos>()
        val maxBlocks = config.maxTreeSize

        val logVisited = mutableSetOf<BlockPos>()
        val logToVisit = mutableListOf(startPos)

        while (logToVisit.isNotEmpty() && logs.size < maxBlocks) {
            val current = logToVisit.removeAt(0)
            if (current in logVisited) continue
            logVisited.add(current)

            val state = world.getBlockState(current)
            if (isLogBlock(state.block)) {
                logs.add(current)
                for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
                    if (dx == 0 && dy == 0 && dz == 0) continue
                    val neighbor = current.add(dx, dy, dz)
                    if (neighbor !in logVisited) logToVisit.add(neighbor)
                }
            }
        }

        val leafVisited = mutableSetOf<BlockPos>()
        val leafToVisit = mutableListOf<BlockPos>()

        for (log in logs) {
            for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
                if (dx == 0 && dy == 0 && dz == 0) continue
                val neighbor = log.add(dx, dy, dz)
                val state = world.getBlockState(neighbor)
                if (isLeavesBlock(state.block) && neighbor !in leafVisited) {
                    leafToVisit.add(neighbor)
                }
            }
        }

        while (leafToVisit.isNotEmpty() && logs.size + leaves.size < maxBlocks) {
            val current = leafToVisit.removeAt(0)
            if (current in leafVisited) continue
            leafVisited.add(current)

            val state = world.getBlockState(current)
            if (isLeavesBlock(state.block)) {
                leaves.add(current)
                // Check all adjacent blocks for more leaves
                for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
                    if (dx == 0 && dy == 0 && dz == 0) continue
                    val neighbor = current.add(dx, dy, dz)
                    if (neighbor !in leafVisited) {
                        val neighborState = world.getBlockState(neighbor)
                        if (isLeavesBlock(neighborState.block)) {
                            leafToVisit.add(neighbor)
                        }
                    }
                }
            }
        }

        val treeBlocks = (logs + leaves).sortedByDescending { it.y }
        return treeBlocks
    }

    private fun findNearestTree(world: World, origin: BlockPos): BlockPos? {
        val horizontalRadius = 32
        val verticalRange = config.verticalSearchRange
        var best: BlockPos? = null
        var bestD = Double.MAX_VALUE
        BlockPos.iterate(origin.add(-horizontalRadius, -verticalRange, -horizontalRadius), origin.add(horizontalRadius, verticalRange, horizontalRadius)).forEach { p ->
            val d = p.getSquaredDistance(origin)
            if (d < bestD && isLogBlock(world.getBlockState(p).block) && isTreeBase(world, p) && hasLeavesNearby(world, p)) {
                if (!CobbleworkersNavigationUtils.isTargeted(p, world)) {
                    best = p.toImmutable()
                    bestD = d
                }
            }
        }
        return best
    }

    private fun isLogBlock(b: Block) = b is PillarBlock && b.defaultState.isIn(BlockTags.LOGS)
    private fun isLeavesBlock(b: Block) = b is LeavesBlock || b.defaultState.isIn(BlockTags.LEAVES)
    private fun isTreeBase(w: World, p: BlockPos) = w.getBlockState(p.down()).isIn(BlockTags.DIRT)

    private fun isDesignatedFeller(pokemonEntity: PokemonEntity): Boolean {
        val speciesName = pokemonEntity.pokemon.species.translatedName.string.lowercase()
        return config.treeFellers.any { it.lowercase() == speciesName }
    }

    override fun isActivelyWorking(p: PokemonEntity) = pokemonFellingTrees.containsKey(p.pokemon.uuid) || (heldItemsByPokemon[p.pokemon.uuid]?.isNotEmpty() == true)

    override fun interrupt(p: PokemonEntity, world: World) {
        val uuid = p.pokemon.uuid
        val state = pokemonFellingTrees[uuid]

        if (state != null) {
            state.leafPositions.forEach { pos ->
                val blockState = world.getBlockState(pos)
                if (blockState.block is LeavesBlock && blockState.contains(LeavesBlock.PERSISTENT)) {
                    world.setBlockState(pos, blockState.with(LeavesBlock.PERSISTENT, false), Block.NOTIFY_LISTENERS)
                }
            }
        }

        pokemonFellingTrees.remove(uuid)
        CobbleworkersNavigationUtils.releaseTarget(uuid, world)
    }
}