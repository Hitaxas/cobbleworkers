/*
 * Copyright (C) 2026 HitaxasTV
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package accieo.cobbleworkers.network.fabric

import accieo.cobbleworkers.network.payloads.ToggleWorkPayload
import accieo.cobbleworkers.sanity.SanitySyncPayload
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry

object CobbleworkersFabricPackets {

    fun registerCommon() {
        // Client → Server
        PayloadTypeRegistry.playC2S().register(
            SanitySyncPayload.ID,
            SanitySyncPayload.CODEC
        )

        PayloadTypeRegistry.playC2S().register(
            ToggleWorkPayload.ID,
            ToggleWorkPayload.CODEC
        )

        // Server → Client
        PayloadTypeRegistry.playS2C().register(
            SanitySyncPayload.ID,
            SanitySyncPayload.CODEC
        )
    }
}