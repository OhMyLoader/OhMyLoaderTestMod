package oml.playmod

import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import org.ohmyloader.api26_3.OMLGame
import org.ohmyloader.api26_3.levelById
import org.ohmyloader.api26_3.onlinePlayers
import org.ohmyloader.api26_3.playerByName

/**
 * The few lookups the rest of the kit needs, all of them the game's own objects: the running server, a named
 * dimension's level, a player by name. Everything else is an extension call on what these return.
 */
object PlayLookups {

    val server: MinecraftServer? get() = OMLGame.server

    fun level(dimensionId: String): ServerLevel? = server?.levelById(dimensionId)

    fun playerByName(name: String): ServerPlayer? = server?.playerByName(name)

    /** The overworld is where the kit starts, because that is where a new world drops a player. */
    val overworld: ServerLevel? get() = server?.levelById("minecraft:overworld")

    val players: List<ServerPlayer> get() = server?.onlinePlayers ?: emptyList()

    /** Any player, for the paths a dedicated server still has to be able to answer about. */
    val firstPlayer: ServerPlayer? get() = players.firstOrNull()
}
