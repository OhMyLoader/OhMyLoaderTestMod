package oml.playmod

import net.minecraft.core.BlockPos
import net.minecraft.world.entity.player.Player
import java.util.UUID

/**
 * The kit's session memory: who set a home, which pad fired, how many chunks are live per dimension, and what
 * the overlay says. A machine's persistent half lives in its own block entity instead, so nothing here survives
 * a restart — which is what a player expects from "where my pearl goes this session".
 */
object PlayState {

    private val homes = mutableMapOf<UUID, Pair<String, BlockPos>>()
    private val activations = mutableMapOf<UUID, Int>()

    /** Pads that fired, keyed `dimensionId:x,y,z`. */
    val padsFired = mutableMapOf<String, Int>()

    /** Chunks currently ticking per dimension, moved by the two chunk events. */
    val tickingChunks = mutableMapOf<String, Int>()

    /** The line the overlay shows, plus whether it is shown at all. */
    @Volatile
    var overlayLine: String = "pick a waystone, then throw a pearl"

    @Volatile
    var overlayVisible: Boolean = true

    /** Counters the commands report on: what the kit did since the world started. */
    @Volatile
    var lastMenu: String? = null

    @Volatile
    var lastChat: String = ""

    @Volatile
    var shocksDealt: Int = 0

    @Volatile
    var pearlsThrown: Int = 0

    /** Ticks the world has run and players it holds, kept fresh by [PlayEvents] so the panel answers from one
     * snapshot instead of scanning the world per request. */
    @Volatile
    var serverTicks: Int = 0

    @Volatile
    var playersOnline: Int = 0

    fun homeOf(player: Player): Pair<String, BlockPos>? = homes[player.uuid]

    fun setHome(player: Player, dimensionId: String, position: BlockPos) {
        homes[player.uuid] = dimensionId to position
    }

    fun clearHome(player: Player) {
        homes.remove(player.uuid)
    }

    fun noteActivation(player: Player): Int =
        activations.merge(player.uuid, 1) { old, _ -> old + 1 } ?: 1

    fun activationsOf(player: Player): Int = activations[player.uuid] ?: 0

    fun forget(player: Player) {
        homes.remove(player.uuid)
        activations.remove(player.uuid)
    }

    fun padKey(dimensionId: String, position: BlockPos): String =
        "$dimensionId:${position.x},${position.y},${position.z}"
}
