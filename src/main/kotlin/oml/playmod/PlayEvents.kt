package oml.playmod

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand
import org.ohmyloader.api.tick.OMLScheduler
import org.ohmyloader.api.tick.OMLSide
import org.ohmyloader.api.tick.OMLTask
import org.ohmyloader.api26_3.*
import org.ohmyloader.api26_3.event.Events

/**
 * What the kit does when the game moves. Every subscription is a reaction a player can see or feel, and a handler
 * that replaces a vanilla action cancels that action so the two do not both run.
 */
object PlayEvents {

    private var chimeTask: OMLTask? = null


    fun register() {
        lifecycle()
        world()
        interaction()
        combat()
        client()
    }

    private fun lifecycle() {
        Events.SERVER_STARTED.register { event ->
            // The gallery is quiet ground until someone builds in it, so the kit keeps its own beat there.
            chimeTask?.cancel()
            chimeTask = OMLScheduler.repeat(OMLSide.SERVER, 1200) { PlayGallery.chime() }
            event.server.broadcastMessage("[kit] the playground is awake")
        }
        Events.SERVER_STOPPING.register {
            chimeTask?.cancel()
            chimeTask = null
            println("[oml_playmod] closing after ${PlayState.padsFired.size} pad firings")
        }
        Events.PLAYER_JOINED.register { event ->
            val player = event.player
            // The kit's own permission node: nothing in vanilla grants it, so the mod does, per player.
            val granted = player.grantPermissionNode(PlayCommands.KEEPER_NODE)
            if (PlayConfig.announceJoins) {
                player.sendSystemMessage(
                    "kit: claim a waystone, then press J for your panel (keeper node granted=$granted)",
                )
            }
            // Scheduled rather than immediate: the inventory is not settled the instant a player joins.
            OMLScheduler.schedule(OMLSide.SERVER, 40) {
                if (!player.isRemoved) player.playSound("minecraft:ui.toast.challenge_complete", 0.5f, 1.4f)
            }
        }
        Events.PLAYER_LEFT.register { event -> PlayState.forget(event.player) }
        // The kit's own heartbeat. Everything a machine does runs on its block ticker and everything timed runs
        // on the scheduler, so what is left for the world's tick is the one number that has no owner otherwise:
        // how long this world has been up, and who is in it.
        Events.SERVER_TICK.register {
            PlayState.serverTicks++
            PlayState.playersOnline = PlayLookups.players.size
        }
        Events.PLAYER_RESPAWNED.register { event ->
            // The instance the server made for this life is the one that has to be told where it goes home to.
            val home = PlayState.homeOf(event.player)
            event.player.sendSystemMessage(
                if (home == null) "kit: no waystone claimed yet"
                else "kit: home is ${home.second} in ${home.first}",
            )
            PlayState.overlayLine = "respawned in ${event.player.dimensionId}"
        }
    }

    private fun world() {
        Events.LEVEL_TICK.register { event ->
            val level = event.level as? ServerLevel ?: return@register
            if (level.dimensionId != PlayGallery.DIMENSION_ID || level.server.tickCount % 200 != 0) return@register
            val instance = level.clockInstance ?: return@register
            level.blockDataAt(PlayGallery.WAYSTONE_AT)?.putLong("gallery_beat", instance.totalTicks())
        }
        Events.CHUNK_STARTED_TICKING.register { PlayGallery.chunkTicked() }
        Events.CHUNK_UNLOADED.register { PlayGallery.chunkUnloaded() }
        Events.BLOCK_BREAK.register { event ->
            val id = event.state.blockId
            if (!id.startsWith("oml_playmod:")) return@register
            event.player.sendSystemMessage(
                "kit: broke $id at ${event.position.x},${event.position.y},${event.position.z}",
            )
            // The block entity goes with the block, so a waystone that stood there owns no home any more.
            PlayCommands.forgetHomeAt(event.position)
        }
    }

    private fun interaction() {
        Events.BLOCK_USED.register { event ->
            val level = event.player.level()
            val position = event.hit.blockPos
            when (level.blockIdAt(position)) {
                PlayMachines.WAYSTONE -> {
                    PlayMachines.claimWaystone(level, position, event.player)
                    event.cancel()
                }

                PlayMachines.PAD -> {
                    val data = level.blockDataAt(position) ?: return@register
                    data.putInt("charge", (data.getInt("charge") + 1).coerceAtMost(PlayConfig.padCharge))
                    event.player.sendSystemMessage("kit: pad now holds ${data.getInt("charge")} charges")
                    event.cancel()
                }

                PlayMachines.LITE_BLOCK -> {
                    val data = level.blockDataAt(position) ?: return@register
                    event.player.sendSystemMessage(
                        "kit: pulses ${data.getInt("pulses")}, visits ${data.getInt("visits")}, " +
                            "last stepper ${data.getString("last_stepper") ?: "nobody"}",
                    )
                    event.cancel()
                }

                else -> return@register
            }
        }
        Events.ITEM_USED.register { event ->
            if (event.hand != InteractionHand.MAIN_HAND) return@register
            when (event.stack.itemId) {
                PlayContent.id(PlayContent.PEARL) -> {
                    event.cancel()
                    event.player.sendSystemMessage(PlayCommands.recall(event.player))
                }

                PlayContent.id(PlayContent.KEY) -> {
                    event.cancel()
                    event.player.sendSystemMessage(PlayGallery.enter(event.player))
                }

                else -> Unit
            }
        }
        Events.ENTITY_INTERACT.register { event ->
            // A kit-boosted player greets what they pet; the greeting is the visible half of the effect.
            if (event.player.hasEffect("minecraft:slow_falling")) {
                event.player.playSound("minecraft:entity.cat.purr", 0.6f, 1.0f)
            }
        }
        Events.GUI_OPEN.register { event ->
            PlayState.lastMenu = event.screen?.javaClass?.simpleName
            if (event.screen == null) PlayState.overlayLine = "closed the menu"
        }
    }

    private fun combat() {
        Events.ENTITY_ATTACK.register { event ->
            val level = event.player.level()
            level.playSoundAt(
                "minecraft:entity.arrow.hit_player",
                event.target.x, event.target.y + 0.5, event.target.z, 0.3f, 1.6f, "player",
            )
            PlayState.noteActivation(event.player)
        }
        Events.LIVING_HURT.register { event ->
            if (!event.source.type().msgId().contains("shock")) return@register
            PlayState.shocksDealt++
            val level = event.entity.level() as? ServerLevel ?: return@register
            level.spawnDustParticles(0xEAE86D, 1.1f, event.entity.x, event.entity.y + 0.6, event.entity.z, 8)
        }
        Events.LIVING_DIED.register { event ->
            val player = event.entity as? net.minecraft.server.level.ServerPlayer ?: return@register
            PlayState.overlayLine = "you died to ${event.source.type().msgId()}"
            player.sendSystemMessage("kit: the waystone still points you home")
        }
    }

    private fun client() {
        Events.CLIENT_TICK.register {
            val client = OMLGame.client ?: return@register
            client.currentScreen?.let { PlayState.lastMenu = it.javaClass.simpleName }
        }
        Events.WORLD_LOAD.register { event ->
            if (event.world == null) {
                PlayState.overlayLine = "left the world"
                PlayGallery.forgetCounters()
            } else {
                PlayState.overlayLine = "world ready — type /kit"
            }
        }
        Events.CHAT_SENT.register { event ->
            // A leading slash is a command, and the kit has nothing to add to one.
            if (!event.message.startsWith("/")) PlayState.lastChat = event.message
        }
        Events.CHAT_RECEIVED.register { event ->
            if (event.message.contains("kit:")) PlayState.overlayLine = event.message
        }
        Events.FRAME_RATE_LIMIT.register { event ->
            val cap = PlayConfig.maxFps
            if (cap > 0 && event.limit > cap) event.limit = cap
        }
        Events.HUD_RENDER.register { event ->
            if (!PlayState.overlayVisible) return@register
            val client = OMLGame.client ?: return@register
            PlayOverlay.lines(client.scaledWidth, client.scaledHeight)
                .forEach { [x, y, text] -> event.graphics.drawText(text, x, y) }
        }
    }
}
