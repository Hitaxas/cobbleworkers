/*
 * Copyright (C) 2025 HitaxasTV
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package accieo.cobbleworkers.sanity

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.entity.Entity
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.world.World
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

object SanityManager {

    /* ----------------------------- CONFIG ----------------------------- */

    const val MAX_SANITY = 100.0
    const val COMPLAINING_THRESHOLD = 50.0 // Below this: RNG chance to slack off
    const val REFUSE_THRESHOLD = 50.0
    const val RESUME_THRESHOLD = 60.0

    // for some reason, values lower than 0.1 seem to not update/allow the HUD to show...
    private const val WORK_DRAIN_PER_TICK =  0.0104 // 0.000625 // around 0.0125/second
    private const val REST_RECOVERY_PER_TICK = 0.025 // around 0.5/second
    private const val SLEEP_RECOVERY_MULTIPLIER = 3.5 // 3.5x faster when sleeping
    private const val MIN_BREAK_DURATION_TICKS = 20L * 60L // 1 minute minimum

    // RNG chances for slacking behavior (50-30% sanity range)
    private const val SLACK_CHANCE_AT_50 = 0.05 // 5% chance per tick at 50%
    private const val SLACK_CHANCE_AT_30 = 1.00 // 100% chance per tick at 30%

    // Sleep chance when refusing work (below 50% sanity)
    private const val SLEEP_CHANCE = 0.50 // 50% chance to sleep instead of wandering

    private const val COMPLAINT_CHECK_INTERVAL = 20L * 5L // Check every 5 seconds

    /* ------------------------------ STATE ------------------------------ */

    private val sanity: MutableMap<UUID, Double> = ConcurrentHashMap()
    private val breakStartTime: MutableMap<UUID, Long> = ConcurrentHashMap()
    private val isRefusing: MutableMap<UUID, Boolean> = ConcurrentHashMap()
    private val isSleeping: MutableMap<UUID, Boolean> = ConcurrentHashMap()
    private val lastComplaintCheck: MutableMap<UUID, Long> = ConcurrentHashMap()
    private val hasComplainedDuringThisStretch: MutableMap<UUID, Boolean> = ConcurrentHashMap()

    fun getSanity(pokemon: PokemonEntity): Double {
        return sanity.computeIfAbsent(pokemon.pokemon.uuid) { MAX_SANITY }
    }

    fun isComplaining(pokemon: PokemonEntity): Boolean {
        val currentSanity = getSanity(pokemon)
        return currentSanity < COMPLAINING_THRESHOLD && currentSanity >= REFUSE_THRESHOLD
    }

    /**
     * RNG-based check if Pokemon should slack off this tick.
     * Chance increases as sanity drops from 50% to 30%.
     */
    fun shouldSlackOff(pokemon: PokemonEntity): Boolean {
        val currentSanity = getSanity(pokemon)

        // Only roll when in complaining range (50-30%)
        if (currentSanity >= COMPLAINING_THRESHOLD || currentSanity < REFUSE_THRESHOLD) {
            return false
        }

        // Linear interpolation between 5% at 50% sanity and 20% at 30% sanity
        val sanityRange = COMPLAINING_THRESHOLD - REFUSE_THRESHOLD // 20
        val currentPosition = currentSanity - REFUSE_THRESHOLD // 0-20
        val t = currentPosition / sanityRange // 0.0-1.0
        val slackChance = SLACK_CHANCE_AT_30 + (SLACK_CHANCE_AT_50 - SLACK_CHANCE_AT_30) * t

        return Random.nextDouble() < slackChance
    }

    fun canWork(pokemon: PokemonEntity, world: World): Boolean {
        val uuid = pokemon.pokemon.uuid
        val currentSanity = getSanity(pokemon)

        // Check if on break
        if (isRefusing[uuid] == true) {
            val breakStart = breakStartTime[uuid] ?: return false

            // Can resume if: minimum break time passed AND sanity recovered
            if (world.time - breakStart >= MIN_BREAK_DURATION_TICKS && currentSanity >= RESUME_THRESHOLD) {
                isRefusing[uuid] = false
                isSleeping[uuid] = false
                breakStartTime.remove(uuid)
                sendActionBar(
                    pokemon,
                    "${pokemon.pokemon.getDisplayName().string} is ready to work again.",
                    Formatting.GREEN
                )
                return true
            }

            // Still on break - apply appropriate recovery
            if (isSleeping[uuid] == true) {
                recoverWhileSleeping(pokemon)
            } else {
                recoverWhileIdle(pokemon)
            }

            return false
        }

        // Below threshold - begin refusal
        if (currentSanity < REFUSE_THRESHOLD) {
            beginRefusal(pokemon, world)
            return false
        }

        return true
    }

    fun drainWhileWorking(pokemon: PokemonEntity) {
        val uuid = pokemon.pokemon.uuid
        val currentSanity = getSanity(pokemon)
        sanity[uuid] = max(0.0, currentSanity - WORK_DRAIN_PER_TICK)
    }

    fun recoverWhileIdle(pokemon: PokemonEntity) {
        val uuid = pokemon.pokemon.uuid
        sanity[uuid] = min(MAX_SANITY, getSanity(pokemon) + REST_RECOVERY_PER_TICK)
    }

    fun recoverWhileSleeping(pokemon: PokemonEntity) {
        val uuid = pokemon.pokemon.uuid
        sanity[uuid] = min(MAX_SANITY, getSanity(pokemon) + (REST_RECOVERY_PER_TICK * SLEEP_RECOVERY_MULTIPLIER))
    }

    /**
     * Check if Pokemon should complain this tick.
     * Only complains once per stretch below 50% sanity.
     */
    fun shouldComplain(pokemon: PokemonEntity, world: World): Boolean {
        val uuid = pokemon.pokemon.uuid
        val currentSanity = getSanity(pokemon)

        // Reset complaint flag when back above threshold
        if (currentSanity >= COMPLAINING_THRESHOLD) {
            hasComplainedDuringThisStretch[uuid] = false
            return false
        }

        // Already complained during this low-sanity stretch
        if (hasComplainedDuringThisStretch[uuid] == true) {
            return false
        }

        // Check if enough time has passed since last check
        val lastCheck = lastComplaintCheck[uuid] ?: 0L
        if (world.time - lastCheck < COMPLAINT_CHECK_INTERVAL) {
            return false
        }

        lastComplaintCheck[uuid] = world.time

        // Complain and mark as complained for this stretch
        if (isComplaining(pokemon)) {
            hasComplainedDuringThisStretch[uuid] = true
            return true
        }

        return false
    }

    fun beginRefusal(pokemon: PokemonEntity, world: World) {
        val uuid = pokemon.pokemon.uuid
        if (isRefusing[uuid] != true) {
            isRefusing[uuid] = true
            breakStartTime[uuid] = world.time

            val name = pokemon.pokemon.getDisplayName().string

            // RNG: 50% chance to sleep, 50% chance to just wander/slack
            val willSleep = Random.nextDouble() < SLEEP_CHANCE
            isSleeping[uuid] = willSleep

            if (willSleep) {
                sendActionBar(pokemon, "$name is fast asleep!", Formatting.GOLD)
            } else {
                sendActionBar(pokemon, "$name is slacking off!", Formatting.RED)
            }
        }
    }

    fun needsForcedBreak(pokemon: PokemonEntity): Boolean {
        val currentSanity = getSanity(pokemon)
        return currentSanity < REFUSE_THRESHOLD
    }

    fun isRefusingWork(pokemon: PokemonEntity): Boolean {
        return isRefusing[pokemon.pokemon.uuid] == true
    }

    /**
     * Returns true if Pokemon is currently sleeping during refusal.
     */
    fun isSleepingDuringBreak(pokemon: PokemonEntity): Boolean {
        return isSleeping[pokemon.pokemon.uuid] == true
    }

    fun clear(pokemon: PokemonEntity) {
        val uuid = pokemon.pokemon.uuid
        sanity.remove(uuid)
        breakStartTime.remove(uuid)
        isRefusing.remove(uuid)
        isSleeping.remove(uuid)
        lastComplaintCheck.remove(uuid)
        hasComplainedDuringThisStretch.remove(uuid)
    }

    /**
     * Debug/admin command to set sanity.
     */
    fun setSanity(pokemon: PokemonEntity, value: Double) {
        sanity[pokemon.pokemon.uuid] = value.coerceIn(0.0, MAX_SANITY)
    }

    /**
     * Get a readable status string for debugging or UI display.
     */
    fun getStatus(pokemon: PokemonEntity): String {
        val currentSanity = getSanity(pokemon)
        val refusing = isRefusing[pokemon.pokemon.uuid] == true
        val sleeping = isSleeping[pokemon.pokemon.uuid] == true

        return when {
            refusing && sleeping -> "Sleeping (${currentSanity.toInt()}%)"
            refusing -> "Slacking Off (${currentSanity.toInt()}%)"
            currentSanity < REFUSE_THRESHOLD -> "About to Slack (${currentSanity.toInt()}%)"
            currentSanity < COMPLAINING_THRESHOLD -> "Unhappy (${currentSanity.toInt()}%)"
            currentSanity >= 80 -> "Excellent (${currentSanity.toInt()}%)"
            currentSanity >= 60 -> "Good (${currentSanity.toInt()}%)"
            else -> "Fair (${currentSanity.toInt()}%)"
        }
    }

    /**
     * Get sanity percentage as integer for UI display.
     */
    fun getSanityPercent(pokemon: PokemonEntity): Int {
        return getSanity(pokemon).toInt()
    }

    private fun sendActionBar(pokemon: PokemonEntity, message: String, color: Formatting) {
        val text = Text.literal(message).formatted(color)
        val owner = pokemon.owner
        if (owner is ServerPlayerEntity) {
            owner.sendMessage(text, true)
        }
    }
}