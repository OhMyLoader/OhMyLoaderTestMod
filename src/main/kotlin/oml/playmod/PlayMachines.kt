package oml.playmod

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import org.ohmyloader.api.content.OMLBlockTickEvent
import org.ohmyloader.api26_3.applyEffect
import org.ohmyloader.api26_3.blockDataAt
import org.ohmyloader.api26_3.blockIdAt
import org.ohmyloader.api26_3.dimensionId
import org.ohmyloader.api26_3.playSoundAt
import org.ohmyloader.api26_3.setRespawnAnchor
import org.ohmyloader.api26_3.spawnDustParticles
import org.ohmyloader.api26_3.spawnParticles
import org.ohmyloader.api26_3.typeId

/**
 * What the kit's blocks do. The `level` and `entity` a behavior hook hands over are the game's own objects, so
 * they are cast once here instead of being pushed through another shape; what a machine remembers lives in its
 * `OMLBlockData`, which is the half that survives a restart, while `PlayState` holds the session half.
 */
object PlayMachines {

    const val LITE_BLOCK = "oml_playmod:lite_block"
    const val PAD = "oml_playmod:launch_pad"
    const val WAYSTONE = "oml_playmod:waystone"

    fun tickLiteBlock(event: OMLBlockTickEvent) {
        val data = event.data
        val uptime = data.getInt("uptime") + 1
        data.putInt("uptime", uptime)
        if (uptime % 100 != 0) return
        data.putInt("pulses", data.getInt("pulses") + 1)
        val level = event.level as? ServerLevel ?: return
        level.spawnDustParticles(0x63B400, 1.4f, event.x + 0.5, event.y + 1.2, event.z + 0.5, 6)
    }

    fun touched(level: Any, x: Int, y: Int, z: Int, entity: Any) {
        val serverLevel = level as? ServerLevel ?: return
        val who = entity as? Entity ?: return
        val data = serverLevel.blockDataAt(BlockPos(x, y, z)) ?: return
        val visits = data.getInt("visits") + 1
        data.putInt("visits", visits)
        data.putString("last_stepper", labelOf(who))
        serverLevel.playSoundAt("minecraft:block.note_block.pling", x + 0.5, y + 1.0, z + 0.5, 0.6f, 1.4f)
        PlayState.overlayLine = "${labelOf(who)} stepped on it; visit $visits"
    }

    fun punched(level: Any, x: Int, y: Int, z: Int, player: Any) {
        val serverLevel = level as? ServerLevel ?: return
        val who = player as? Player ?: return
        val data = serverLevel.blockDataAt(BlockPos(x, y, z)) ?: return
        data.putString("last_punch", who.gameProfile.name)
        serverLevel.spawnDustParticles(0xD7FF5E, 1.0f, x + 0.5, y + 0.9, z + 0.5, 4)
        PlayState.noteActivation(who)
    }

    fun launch(level: Any, x: Int, y: Int, z: Int, entity: Any) {
        val serverLevel = level as? ServerLevel ?: return
        val who = entity as? LivingEntity ?: return
        val position = BlockPos(x, y, z)
        val data = serverLevel.blockDataAt(position) ?: return
        val charge = data.getInt("charge")
        if (charge <= 0) {
            serverLevel.playSoundAt(
                "minecraft:block.note_block.didgeridoo", x + 0.5, y + 0.6, z + 0.5, 0.5f, 0.6f,
            )
            return
        }
        data.putInt("charge", charge - 1)
        data.putInt("launches", data.getInt("launches") + 1)
        data.putInt("rearm", 0)
        who.setDeltaMovement(who.deltaMovement.x, PlayConfig.padLaunch, who.deltaMovement.z)
        who.applyEffect("minecraft:slow_falling", 140)
        serverLevel.spawnParticles(
            "minecraft:cloud", x + 0.5, y + 1.1, z + 0.5, 12, 0.3, 0.15, 0.3, 0.02,
        )
        serverLevel.playSoundAt("minecraft:entity.generic.swim", x + 0.5, y + 1.0, z + 0.5, 0.7f, 1.6f)
        PlayState.padsFired.merge(PlayState.padKey(serverLevel.dimensionId, position), 1) { old, _ -> old + 1 }
        PlayState.overlayLine = "thrown: ${labelOf(who)}, ${data.getInt("charge")} charges left"
    }

    /** A pad takes one charge back every `pad_rearm_ticks`, which is the machine's whole job. */
    fun rearmPad(event: OMLBlockTickEvent) {
        val data = event.data
        val charge = data.getInt("charge")
        if (charge >= PlayConfig.padCharge) {
            if (data.hasKey("rearm")) data.remove("rearm")
            return
        }
        val counting = data.getInt("rearm") + 1
        if (counting < PlayConfig.padRearmTicks) {
            data.putInt("rearm", counting)
            return
        }
        data.putInt("charge", charge + 1)
        data.remove("rearm")
        val level = event.level as? ServerLevel ?: return
        level.spawnDustParticles(0x7CE0A0, 0.8f, event.x + 0.5, event.y + 0.7, event.z + 0.5, 3)
    }

    fun waystoneHeartbeat(event: OMLBlockTickEvent) {
        event.data.putInt("heartbeat", event.data.getInt("heartbeat") + 1)
    }

    /**
     * The waystone's right click. The pearl's target and the player's respawn anchor are set together because
     * they mean the same thing to a player — "here is where I come back to" — and the anchor write reports what
     * the game actually took.
     */
    fun claimWaystone(level: ServerLevel, position: BlockPos, player: Player): Boolean {
        val data = level.blockDataAt(position) ?: return false
        data.putString("keeper", player.gameProfile.name)
        data.putInt("visits", data.getInt("visits") + 1)
        val target = position.above()
        PlayState.setHome(player, level.dimensionId, target)
        val anchored = (player as? ServerPlayer)
            ?.setRespawnAnchor(level.dimensionId, target, player.yRot, 0f, announce = false) == true
        level.playSoundAt("minecraft:block.beacon.activate", position.x + 0.5, position.y + 1.0, position.z + 0.5)
        level.spawnDustParticles(0x59E0C4, 1.2f, position.x + 0.5, position.y + 1.3, position.z + 0.5, 10)
        PlayState.overlayLine = if (anchored) "waystone claimed: home + respawn anchor" else "home set, anchor refused"
        return anchored
    }

    /** A mob is addressed by its type id, a player by their profile name; both are what a log line needs. */
    private fun labelOf(entity: Entity): String =
        (entity as? Player)?.gameProfile?.name ?: entity.typeId

    /** Whether the block under a player's feet is one of the kit's, with the data to prove it. */
    fun machineUnder(player: Player): Pair<ServerLevel, BlockPos>? {
        val level = player.level() as? ServerLevel ?: return null
        val below = player.blockPosition().below()
        val id = level.blockIdAt(below)
        if (id != PAD && id != LITE_BLOCK && id != WAYSTONE) return null
        return if (level.blockDataAt(below) != null) level to below else null
    }

    /** The pad in front of a player, which `/kit pad` charges on purpose rather than by accident. */
    fun padAt(level: ServerLevel, position: BlockPos): Boolean = level.blockIdAt(position) == PAD
}
