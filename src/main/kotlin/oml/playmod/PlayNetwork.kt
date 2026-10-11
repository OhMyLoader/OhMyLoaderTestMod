package oml.playmod

import net.minecraft.server.level.ServerLevel
import org.ohmyloader.api.network.OMLNetwork
import org.ohmyloader.api.network.OMLNetworkRegistry
import org.ohmyloader.api.network.OMLPacketBuffer
import org.ohmyloader.api.network.OMLPayloadCodec
import org.ohmyloader.api.network.OMLPayloadType
import org.ohmyloader.api26_3.blockIdAt
import org.ohmyloader.api26_3.dimensionId

/**
 * The two messages the kit speaks: a player asking for their panel, which the key bind sends, and the panel
 * coming back down. The server side reads the sender off the context instead of assuming who asked, because the
 * same handler also answers a console caller — which has no client to send to.
 */
object PlayNetwork {

    val PANEL_REQUEST = OMLPayloadType("oml_playmod:panel_request", StringCodec)
    val PANEL_REPLY = OMLPayloadType("oml_playmod:panel_reply", PanelCodec)

    /** What the client renders until the next answer replaces it. */
    @Volatile
    var panel: String? = null

    data class Panel(
        val headline: String,
        val pads: Int,
        val ticks: Int,
        val online: Int,
        val hasHome: Boolean,
        val dimension: String,
        val menu: String,
    )

    private object StringCodec : OMLPayloadCodec<String> {
        override fun write(buffer: OMLPacketBuffer, value: String) = buffer.writeString(value)
        override fun read(buffer: OMLPacketBuffer): String = buffer.readString()
    }

    private object PanelCodec : OMLPayloadCodec<Panel> {
        override fun write(buffer: OMLPacketBuffer, value: Panel) {
            buffer.writeString(value.headline)
            buffer.writeInt(value.pads)
            buffer.writeInt(value.ticks)
            buffer.writeInt(value.online)
            buffer.writeBoolean(value.hasHome)
            buffer.writeString(value.dimension)
            buffer.writeString(value.menu)
        }

        override fun read(buffer: OMLPacketBuffer): Panel =
            Panel(
                buffer.readString(), buffer.readInt(), buffer.readInt(), buffer.readInt(),
                buffer.readBoolean(), buffer.readString(), buffer.readString(),
            )
    }

    fun declare(network: OMLNetworkRegistry) {
        network.clientToServer(PANEL_REQUEST) { reason, context ->
            val name = context.senderName
            if (name == null) {
                println("[oml_playmod] a panel request from [$reason] arrived with no player behind it")
                return@clientToServer
            }
            val player = PlayLookups.playerByName(name) ?: return@clientToServer
            val standing = (player.level() as? ServerLevel)
                ?.let { it.blockIdAt(player.blockPosition().below()) } ?: "open air"
            val where = player.dimensionId + ":"
            OMLNetwork.sendToPlayer(
                PANEL_REPLY,
                Panel(
                    headline = if (reason.isBlank()) "standing on $standing" else "$reason on $standing",
                    pads = PlayState.padsFired.keys.count { it.startsWith(where) },
                    ticks = PlayState.serverTicks,
                    online = PlayState.playersOnline,
                    hasHome = PlayState.homeOf(player) != null,
                    dimension = player.dimensionId,
                    menu = PlayState.lastMenu ?: "no menu",
                ),
                name,
            )
        }
        network.serverToClient(PANEL_REPLY) { value, _ ->
            panel = buildString {
                append(value.headline)
                append(" · pads ")
                append(value.pads)
                append(" · up ")
                append(value.ticks)
                append("t · ")
                append(value.online)
                append(" online")
                append(if (value.hasHome) " · home set" else " · no home")
                append(" · ").append(value.dimension)
                append(" · ").append(value.menu)
            }
        }
    }

    /** The key bind's whole job: ask the server for the panel. */
    fun requestPanel(reason: String) {
        OMLNetwork.sendToServer(PANEL_REQUEST, reason)
    }

    /** The server-side push, used by `/kit panel` so two players see the same readout. */
    fun broadcastPanel(headline: String) {
        OMLNetwork.sendToAllPlayers(
            PANEL_REPLY,
            Panel(headline, PlayState.padsFired.size, PlayState.serverTicks, PlayState.playersOnline,
                false, PlayGallery.DIMENSION_ID, "no menu"),
        )
    }
}
