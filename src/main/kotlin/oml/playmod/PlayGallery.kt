package oml.playmod

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import org.ohmyloader.api26_3.applyMarker
import org.ohmyloader.api26_3.blockDataAt
import org.ohmyloader.api26_3.clockById
import org.ohmyloader.api26_3.clockInstance
import org.ohmyloader.api26_3.dimensionId
import org.ohmyloader.api26_3.entitiesOfType
import org.ohmyloader.api26_3.forceLoadChunk
import org.ohmyloader.api26_3.isForceLoaded
import org.ohmyloader.api26_3.instanceOf
import org.ohmyloader.api26_3.levelById
import org.ohmyloader.api26_3.markerIdsDriving
import org.ohmyloader.api26_3.markersOf
import org.ohmyloader.api26_3.playSoundAt
import org.ohmyloader.api26_3.setBlockAt
import org.ohmyloader.api26_3.spawnDustParticles
import org.ohmyloader.api26_3.teleport

/**
 * The gallery — the dimension the kit declares, and the place where its clock and timeline are observable. The
 * ground is flat generator air, so the kit puts its own blocks down and force-loads the column first: nothing
 * else ever visits this dimension, and a chunk nobody has loaded holds no block entity to write data into.
 */
object PlayGallery {

    const val DIMENSION_ID = "oml_playmod:" + PlayContent.DIMENSION

    /**
     * The room, in the floor the flat generator already raises to y=4: a strip of the kit's own block, a
     * waystone two steps along, and a pad at the end — so a player who arrives at [ENTER_AT] is standing on the
     * pad, which is the only way the pad's step-on hook is ever reached.
     */
    val FLOOR_AT = BlockPos(0, 4, 0)
    val WAYSTONE_AT = BlockPos(2, 4, 0)
    val PAD_AT = BlockPos(3, 4, 0)
    val ENTER_AT = BlockPos(3, 5, 0)

    /** Chunk transitions since the world loaded; only the two chunk events move these. */
    @Volatile
    var chunksTicked = 0
        private set

    @Volatile
    var chunksUnloaded = 0
        private set

    fun chunkTicked() {
        chunksTicked++
    }

    fun chunkUnloaded() {
        chunksUnloaded++
    }

    fun forgetCounters() {
        chunksTicked = 0
        chunksUnloaded = 0
    }

    val level: ServerLevel? get() = PlayLookups.server?.levelById(DIMENSION_ID)

    /** Lays the room out: a floor the kit declared, a waystone, and a pad beside it. */
    fun build(): String {
        val gallery = level ?: return "no gallery level on this server"
        gallery.forceLoadChunk(ENTER_AT)
        val floor = gallery.setBlockAt(FLOOR_AT, PlayContent.id(PlayContent.BLOCK)) &&
            gallery.setBlockAt(FLOOR_AT.east(), PlayContent.id(PlayContent.BLOCK))
        val waystone = gallery.setBlockAt(WAYSTONE_AT, PlayContent.id(PlayContent.WAYSTONE))
        val pad = gallery.setBlockAt(PAD_AT, PlayContent.id(PlayContent.PAD))
        // The kit's own block cannot light a room — the declaration surface has no light level — so the gallery
        // borrows vanilla's, or the dark floor breeds zombies on top of the kit's guests.
        val lamps = gallery.setBlockAt(FLOOR_AT.above(2), "minecraft:glowstone") &&
            gallery.setBlockAt(PAD_AT.above(2), "minecraft:glowstone")
        return "floor=$floor waystone=$waystone pad=$pad lamps=$lamps in ${gallery.dimensionId} " +
            "at ${ENTER_AT.x},${ENTER_AT.y},${ENTER_AT.z}"
    }

    fun enter(player: ServerPlayer): String {
        val gallery = level ?: return "no gallery level on this server"
        val moved = player.teleport(ENTER_AT.x + 0.5, ENTER_AT.y.toDouble(), ENTER_AT.z + 0.5, DIMENSION_ID)
        if (!moved) {
            return "the gallery refused the move from ${player.dimensionId} ${player.blockPosition()}: " +
                "loaded=${gallery.isLoaded(ENTER_AT)} forced=${gallery.isForceLoaded(ENTER_AT)} " +
                "border=${gallery.worldBorder.isWithinBounds(ENTER_AT)} serverTick=${gallery.server.tickCount}"
        }
        val here = player.level() as? ServerLevel ?: return "moved, but not onto a server level"
        here.playSoundAt("minecraft:block.portal.travel", player.x, player.y, player.z, 0.5f, 1.2f)
        val reachable = here.blockDataAt(WAYSTONE_AT) != null
        return "in ${here.dimensionId} at ${describe(player)}, waystone data reachable=$reachable"
    }

    fun leave(player: ServerPlayer): String {
        val overworld = PlayLookups.overworld ?: return "no overworld to go back to"
        val moved = player.teleport(0.5, 70.0, 0.5, overworld.dimensionId)
        if (!moved) {
            return "the overworld refused the move from ${player.dimensionId} ${player.blockPosition()}: " +
                "loaded=${overworld.isLoaded(BlockPos(0, 70, 0))} " +
                "border=${overworld.worldBorder.isWithinBounds(BlockPos(0, 70, 0))}"
        }
        return "back in ${player.dimensionId} at ${describe(player)}"
    }

    /**
     * The kit's own beat: park the gallery clock on the marker the kit declared. A marker the game does not know
     * is refused, which is why the check comes before the write rather than after.
     */
    fun chime() {
        val gallery = level ?: return
        val server = gallery.server
        val clock = server.clockById(PlayContent.id(PlayContent.CLOCK)) ?: return
        val manager = gallery.clockManager()
        val marker = PlayConfig.galleryMarker
        if (!manager.markersOf(clock).contains(marker)) return
        manager.applyMarker(clock, marker)
        val players = gallery.entitiesOfType("minecraft:player")
        if (players.isEmpty()) return
        gallery.playSoundAt("minecraft:block.bell.resonate", 0.5, 6.0, 0.5, 1.0f, 1.0f)
        gallery.spawnDustParticles(0x8FD8FF, 1.5f, 0.5, 7.0, 0.5, 20)
    }

    /** What the room reports: its clock, the markers on it, and the chunks that came alive. */
    fun status(): String {
        val gallery = level ?: return "no gallery level on this server"
        val server = gallery.server
        val clock = server.clockById(PlayContent.id(PlayContent.CLOCK))
        val ticks = clock?.let { gallery.clockManager().instanceOf(it)?.totalTicks() }
        val markers = clock?.let { gallery.clockManager().markersOf(it) } ?: emptyList()
        val driving = server.markerIdsDriving(PlayContent.id(PlayContent.TIMELINE))
        return "clock=${clock != null} ticks=$ticks markers=${markers.sorted()} on-timeline=$driving " +
            "instance=${gallery.clockInstance != null} chunks=$chunksTicked/$chunksUnloaded " +
            "floor=${gallery.blockDataAt(FLOOR_AT) != null}"
    }

    private fun describe(entity: net.minecraft.world.entity.Entity): String =
        "${entity.blockPosition().x},${entity.blockPosition().y},${entity.blockPosition().z}"
}
