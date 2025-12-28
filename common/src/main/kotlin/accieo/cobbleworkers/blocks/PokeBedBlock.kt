/*
 * Copyright (C) 2025 HitaxasTV
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package accieo.cobbleworkers.blocks

import accieo.cobbleworkers.pokebed.PokeBedManager
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import net.minecraft.block.*
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.item.ItemPlacementContext
import net.minecraft.server.world.ServerWorld
import net.minecraft.state.StateManager
import net.minecraft.state.property.DirectionProperty
import net.minecraft.state.property.Properties
import net.minecraft.text.Text
import net.minecraft.util.ActionResult
import net.minecraft.util.BlockMirror
import net.minecraft.util.BlockRotation
import net.minecraft.util.hit.BlockHitResult
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.util.shape.VoxelShape
import net.minecraft.util.shape.VoxelShapes
import net.minecraft.world.BlockView
import net.minecraft.world.World

/**
 * PokeBed - A decorative bed block for Pokémon to rest and recover sanity faster.
 * Does not set spawn point, purely decorative/functional for worker Pokémon.
 */
class PokeBedBlock(settings: Settings) : Block(settings) {

    companion object {
        val FACING: DirectionProperty = Properties.HORIZONTAL_FACING

        // Bed shape - slightly raised off the ground (3 pixels high)
        private val SHAPE = VoxelShapes.cuboid(0.0, 0.0, 0.0, 1.0, 0.1875, 1.0)
    }

    init {
        defaultState = stateManager.defaultState.with(FACING, Direction.NORTH)
    }

    override fun appendProperties(builder: StateManager.Builder<Block, BlockState>) {
        builder.add(FACING)
    }

    override fun getPlacementState(ctx: ItemPlacementContext): BlockState? {
        return defaultState.with(FACING, ctx.horizontalPlayerFacing.opposite)
    }

    override fun rotate(state: BlockState, rotation: BlockRotation): BlockState {
        return state.with(FACING, rotation.rotate(state.get(FACING)))
    }

    override fun mirror(state: BlockState, mirror: BlockMirror): BlockState {
        return state.rotate(mirror.getRotation(state.get(FACING)))
    }

    override fun getOutlineShape(
        state: BlockState,
        world: BlockView,
        pos: BlockPos,
        context: ShapeContext
    ): VoxelShape {
        return SHAPE
    }

    override fun onUse(
        state: BlockState,
        world: World,
        pos: BlockPos,
        player: PlayerEntity,
        hit: BlockHitResult
    ): ActionResult {
        if (world.isClient) {
            return ActionResult.SUCCESS
        }

        // Find if any Pokemon is using this bed
        val occupantName = findOccupantName(world, pos)

        val message = if (occupantName != null) {
            Text.literal("$occupantName is sleeping here")
        } else if (PokeBedManager.isBedClaimed(pos)) {
            Text.literal("This bed is claimed")
        } else {
            Text.literal("PokeBed - Helps Pokémon recover sanity faster!")
        }

        player.sendMessage(message, true)
        return ActionResult.CONSUME
    }

    /**
     * Find the name of the Pokemon using this bed.
     */
    private fun findOccupantName(world: World, bedPos: BlockPos): String? {
        if (world !is ServerWorld) return null

        // Check if bed is claimed
        if (!PokeBedManager.isBedClaimed(bedPos)) return null

        // Find the Pokemon claiming this bed
        world.iterateEntities().forEach { entity ->
            if (entity is PokemonEntity) {
                val claimedBed = PokeBedManager.getClaimedBed(entity.pokemon.uuid)
                if (claimedBed == bedPos) {
                    return entity.pokemon.getDisplayName().string
                }
            }
        }

        return null
    }
}