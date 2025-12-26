/*
 * Copyright (C) 2025 HitaxasTV
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package accieo.cobbleworkers.sanity

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.cobblemon.mod.common.entity.PoseType
import net.minecraft.server.network.ServerPlayerEntity
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
    const val COMPLAINING_THRESHOLD = 50.0
    const val REFUSE_THRESHOLD = 50.0
    const val RESUME_THRESHOLD = 60.0

    private val sleepYaw: MutableMap<UUID, Float> = ConcurrentHashMap()

    private const val WORK_DRAIN_PER_TICK = 0.0104
    private const val REST_RECOVERY_PER_TICK = 0.025
    private const val SLEEP_RECOVERY_MULTIPLIER = 3.5
    private const val MIN_BREAK_DURATION_TICKS = 20L * 60L

    private const val SLACK_CHANCE_AT_50 = 0.25
    private const val SLACK_CHANCE_AT_30 = 1.00
    private const val SLEEP_CHANCE = 0.50

    private const val COMPLAINT_CHECK_INTERVAL = 20L * 5L

    /* ------------------------------ STATE ------------------------------ */

    private val sanity: MutableMap<UUID, Double> = ConcurrentHashMap()
    private val breakStartTime: MutableMap<UUID, Long> = ConcurrentHashMap()
    private val isRefusing: MutableMap<UUID, Boolean> = ConcurrentHashMap()
    private val isSleeping: MutableMap<UUID, Boolean> = ConcurrentHashMap()
    private val lastComplaintCheck: MutableMap<UUID, Long> = ConcurrentHashMap()
    private val hasComplainedDuringThisStretch: MutableMap<UUID, Boolean> = ConcurrentHashMap()


    /* ===================================================================
       TRUE PERSISTENT STORAGE
       =================================================================== */

    fun getSanity(pokemon: PokemonEntity): Double {
        val uuid = pokemon.pokemon.uuid

        return sanity.computeIfAbsent(uuid) {
            val tag = pokemon.pokemon.persistentData
            if (tag.contains("cobbleworkers_sanity")) {
                tag.getDouble("cobbleworkers_sanity")
                    .coerceIn(0.0, MAX_SANITY)
            } else {
                MAX_SANITY
            }
        }
    }

    private fun persistSanity(pokemon: PokemonEntity, value: Double) {
        val clamped = value.coerceIn(0.0, MAX_SANITY)
        sanity[pokemon.pokemon.uuid] = clamped

        try {
            pokemon.pokemon.persistentData.putDouble("cobbleworkers_sanity", clamped)
        } catch (_: Exception) {}
    }

    /* ===================================================================
       LOGIC
       =================================================================== */

    fun isComplaining(pokemon: PokemonEntity): Boolean {
        val currentSanity = getSanity(pokemon)
        return currentSanity < COMPLAINING_THRESHOLD && currentSanity >= REFUSE_THRESHOLD
    }

    fun shouldSlackOff(pokemon: PokemonEntity): Boolean {
        val currentSanity = getSanity(pokemon)

        if (currentSanity >= COMPLAINING_THRESHOLD || currentSanity < REFUSE_THRESHOLD)
            return false

        val sanityRange = COMPLAINING_THRESHOLD - REFUSE_THRESHOLD
        val currentPosition = currentSanity - REFUSE_THRESHOLD
        val t = currentPosition / sanityRange

        val slackChance =
            SLACK_CHANCE_AT_30 + (SLACK_CHANCE_AT_50 - SLACK_CHANCE_AT_30) * t

        return Random.nextDouble() < slackChance
    }

    fun canWork(pokemon: PokemonEntity, world: World): Boolean {
        val uuid = pokemon.pokemon.uuid
        val currentSanity = getSanity(pokemon)

        if (isRefusing[uuid] == true) {
            val breakStart = breakStartTime[uuid] ?: return false

            if (world.time - breakStart >= MIN_BREAK_DURATION_TICKS && currentSanity >= RESUME_THRESHOLD) {
                isRefusing[uuid] = false
                isSleeping[uuid] = false
                breakStartTime.remove(uuid)

                clearSleepPose(pokemon)

                sendActionBar(
                    pokemon,
                    "${pokemon.pokemon.getDisplayName().string} is ready to work again.",
                    Formatting.GREEN
                )
                return true
            }

            if (isSleeping[uuid] == true) {
                forceSleepPose(pokemon)
                lockSleepRotation(pokemon)
                recoverWhileSleeping(pokemon)
            } else {
                recoverWhileIdle(pokemon)
            }

            persistSanity(pokemon, getSanity(pokemon))
            return false
        }

        if (currentSanity < REFUSE_THRESHOLD) {
            beginRefusal(pokemon, world)
            persistSanity(pokemon, getSanity(pokemon))
            return false
        }

        return true
    }

    fun drainWhileWorking(pokemon: PokemonEntity) {
        val newVal = max(0.0, getSanity(pokemon) - WORK_DRAIN_PER_TICK)
        persistSanity(pokemon, newVal)
    }

    fun recoverWhileIdle(pokemon: PokemonEntity) {
        val newVal = min(MAX_SANITY, getSanity(pokemon) + REST_RECOVERY_PER_TICK)
        persistSanity(pokemon, newVal)
    }

    fun recoverWhileSleeping(pokemon: PokemonEntity) {
        val newVal =
            min(MAX_SANITY, getSanity(pokemon) + (REST_RECOVERY_PER_TICK * SLEEP_RECOVERY_MULTIPLIER))
        persistSanity(pokemon, newVal)
    }

    fun shouldComplain(pokemon: PokemonEntity, world: World): Boolean {
        val uuid = pokemon.pokemon.uuid
        val currentSanity = getSanity(pokemon)

        if (currentSanity >= COMPLAINING_THRESHOLD) {
            hasComplainedDuringThisStretch[uuid] = false
            return false
        }

        if (hasComplainedDuringThisStretch[uuid] == true)
            return false

        val lastCheck = lastComplaintCheck[uuid] ?: 0L
        if (world.time - lastCheck < COMPLAINT_CHECK_INTERVAL)
            return false

        lastComplaintCheck[uuid] = world.time

        if (isComplaining(pokemon)) {
            hasComplainedDuringThisStretch[uuid] = true
            return true
        }

        return false
    }


    fun beginRefusal(pokemon: PokemonEntity, world: World) {
        val uuid = pokemon.pokemon.uuid
        if (isRefusing[uuid] == true)
            return

        isRefusing[uuid] = true
        breakStartTime[uuid] = world.time

        val name = pokemon.pokemon.getDisplayName().string
        val willSleep = Random.nextDouble() < SLEEP_CHANCE

        isSleeping[uuid] = willSleep

        if (willSleep) {
            forceSleepPose(pokemon)
            sendActionBar(pokemon, "$name is fast asleep!", Formatting.GOLD)
        } else {
            sendActionBar(pokemon, "$name is slacking off!", Formatting.RED)
        }
    }


    fun needsForcedBreak(pokemon: PokemonEntity) =
        getSanity(pokemon) < REFUSE_THRESHOLD

    fun isRefusingWork(pokemon: PokemonEntity) =
        isRefusing[pokemon.pokemon.uuid] == true

    fun isSleepingDuringBreak(pokemon: PokemonEntity) =
        isSleeping[pokemon.pokemon.uuid] == true


    fun clear(pokemon: PokemonEntity) {
        val uuid = pokemon.pokemon.uuid
        sanity.remove(uuid)
        breakStartTime.remove(uuid)
        isRefusing.remove(uuid)
        isSleeping.remove(uuid)
        lastComplaintCheck.remove(uuid)
        hasComplainedDuringThisStretch.remove(uuid)
    }


    /* ---------------- Sleep Visual Handling ---------------- */

    private fun forceSleepPose(pokemon: PokemonEntity) {
        try {
            pokemon.navigation?.stop()
            pokemon.velocity = pokemon.velocity.multiply(0.0, 1.0, 0.0)
            pokemon.noClip = false
            pokemon.setNoGravity(false)

            val uuid = pokemon.pokemon.uuid
            sleepYaw.putIfAbsent(uuid, pokemon.yaw)

            pokemon.dataTracker.set(PokemonEntity.POSE_TYPE, PoseType.SLEEP)

        } catch (e: Exception) {
            try {
                pokemon.setPose(net.minecraft.entity.EntityPose.SLEEPING)
            } catch (_: Exception) {}
        }
    }

    private fun clearSleepPose(pokemon: PokemonEntity) {
        try {
            pokemon.dataTracker.set(PokemonEntity.POSE_TYPE, PoseType.STAND)
        } catch (_: Exception) {}

        try {
            pokemon.setPose(net.minecraft.entity.EntityPose.STANDING)
        } catch (_: Exception) {}

        sleepYaw.remove(pokemon.pokemon.uuid)
        pokemon.wakeUp()
    }

    private fun lockSleepRotation(pokemon: PokemonEntity) {
        val yaw = sleepYaw[pokemon.pokemon.uuid] ?: return
        pokemon.yaw = yaw
        pokemon.prevYaw = yaw
        pokemon.headYaw = yaw
        pokemon.bodyYaw = yaw
    }

    /* ---------------- UI + Debug ---------------- */

    fun getStatus(pokemon: PokemonEntity): String {
        val currentSanity = getSanity(pokemon)
        val refusing = isRefusing[pokemon.pokemon.uuid] == true
        val sleeping = isSleeping[pokemon.pokemon.uuid] == true

        val pose = pokemon.dataTracker.get(PokemonEntity.POSE_TYPE)
        if (!refusing && pose == PoseType.SLEEP)
            return "Is fast asleep... (${currentSanity.toInt()}%)"

        return when {
            refusing && sleeping -> "Is fast asleep... (${currentSanity.toInt()}%)"
            refusing -> "Is slacking off! (${currentSanity.toInt()}%)"
            currentSanity < REFUSE_THRESHOLD -> "has just about had it... (${currentSanity.toInt()}%)"
            currentSanity < COMPLAINING_THRESHOLD -> "Unhappy with work conditions. (${currentSanity.toInt()}%)"
            currentSanity >= 80 -> "Is hard at work. (${currentSanity.toInt()}%)"
            currentSanity >= 60 -> "Is in good condition. (${currentSanity.toInt()}%)"
            else -> "Is getting a bit tired... (${currentSanity.toInt()}%)"
        }
    }

    fun getSanityPercent(pokemon: PokemonEntity): Int =
        getSanity(pokemon).toInt()

    private fun sendActionBar(pokemon: PokemonEntity, message: String, color: Formatting) {
        val owner = pokemon.owner
        if (owner is ServerPlayerEntity)
            owner.sendMessage(Text.literal(message).formatted(color), true)
    }
}
