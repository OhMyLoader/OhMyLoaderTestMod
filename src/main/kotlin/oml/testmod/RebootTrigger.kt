package oml.testmod

import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.PauseScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.input.InputWithModifiers
import org.ohmyloader.api26_3.OMLGame
import org.ohmyloader.api26_3.OMLReboot
import org.ohmyloader.api26_3.currentScreen
import org.ohmyloader.api26_3.event.Events

/**
 * Drives the reboot entry points an acceptance run needs, at the tick the run asked for.
 *
 * <p>`-Doml.reboot.afterTicks=<n>` is the existing one-shot switch (the loader's own auto-reboot
 * reads it too); `-Doml.reboot.via=` picks **which door** this session goes through:
 *
 * | via | what it does |
 * |:--|:--|
 * | `api` (default) | calls [OMLReboot.restart] directly — the pre-existing bill path |
 * | `command` | dispatches `/oml reload` as the local player through the integrated server's dispatcher |
 * | `ui` | opens the pause menu, presses the injected **Mods** button, then **Reload Mods** |
 * | `title` | the same two presses on the **title screen** (a run with no `quickPlay`) |
 *
 * <p>The UI variants press the buttons the player would press (`Button.onPress`), so the run also
 * proves the injection put them on the screen: a missing button leaves the trigger waiting, the
 * session never reboots, and the second session's absence is the failure. The properties are
 * cleared by `SessionControl.requestReboot` on the way across, so the receiving session runs the
 * normal suite instead of rebooting again.
 */
object RebootTrigger {

    private val afterTicks = System.getProperty("oml.reboot.afterTicks")?.toIntOrNull()
    private val via = System.getProperty("oml.reboot.via")?.lowercase() ?: "api"

    private var ticks = 0
    private var phase = Phase.WAITING
    private var issued = false
    private var reportedClick = false

    private enum class Phase { WAITING, OPENED, CLICKED_MODS, DONE }

    fun install() {
        if (afterTicks == null) return
        // `api` is the loader's own driver (EventBridge); it stays the single owner of that path, so
        // this trigger only arms when the run asks for a different door.
        if (via == "api") return
        if (via !in setOf("command", "ui", "title")) {
            println("[oml_testmod] reboot trigger: unknown via='$via' (expected api, command, ui or title)")
            return
        }
        println("[oml_testmod] reboot trigger armed: via=$via after=${afterTicks} ticks")
        Events.CLIENT_TICK.register { tick() }
    }

    private fun tick() {
        val target = afterTicks ?: return
        if (issued) return
        val client = OMLGame.client ?: return
        // The precondition describes the moment the trigger *starts*: once the UI path has opened
        // its screens, the place the player is at is no longer "the title screen".
        if (phase == Phase.WAITING && !ready(client, target)) return
        ticks++
        if (ticks < target) return
        when (via) {
            "command" -> issue("command")
            "ui" -> pressThrough(client, worldMenu = true)
            "title" -> pressThrough(client, worldMenu = false)
            else -> issue("api")
        }
    }

    /**
     * The tick the run asked for is only meaningful once the thing it drives exists: `/oml reload`
     * needs an integrated server, the pause-menu variant needs a world, and the title variant needs
     * the title screen. Waiting for that is what keeps the two runs comparable.
     */
    private fun ready(client: net.minecraft.client.Minecraft, target: Int): Boolean = when (via) {
        "ui" -> client.level != null
        "title" -> client.level == null && client.currentScreen is TitleScreen
        // The command path needs the world's player; the api path is the bill's own trigger and
        // historically counts from the first client tick.
        "command" -> client.singleplayerServer != null && client.player != null
        else -> true
    }

    private fun issue(what: String) {
        issued = true
        if (what == "command") {
            val client = OMLGame.client ?: return
            val server = client.singleplayerServer ?: return
            val player = server.playerList.players.firstOrNull { !it.isRemoved } ?: return
            println("[oml_testmod] reboot trigger: /oml reload as ${player.name.string}")
            server.executeIfPossible {
                server.commands.performPrefixedCommand(player.createCommandSourceStack(), "oml reload")
            }
            return
        }
        println("[oml_testmod] reboot trigger: OMLReboot.restart (api) after $ticks ticks")
        OMLReboot.restart("acceptance run ($via)")
    }

    /**
     * The UI path, one step per tick: open the pause menu (the title screen is already open),
     * press its Mods button, then press Reload Mods on the screen that button opened.
     */
    private fun pressThrough(client: net.minecraft.client.Minecraft, worldMenu: Boolean) {
        val screen = client.currentScreen
        when (phase) {
            Phase.WAITING -> {
                if (worldMenu) {
                    if (screen != null) return
                    client.setScreenAndShow(PauseScreen(true))
                }
                phase = Phase.OPENED
            }
            Phase.OPENED -> {
                val current = client.currentScreen ?: return
                if (press(current, MODS_BUTTON)) {
                    println("[oml_testmod] reboot trigger: pressed '$MODS_BUTTON' on ${current.javaClass.simpleName}")
                    phase = Phase.CLICKED_MODS
                }
            }
            Phase.CLICKED_MODS -> {
                val current = client.currentScreen ?: return
                if (!reportedClick) {
                    reportedClick = true
                    val labels = current.children().filterIsInstance<Button>().map { it.message.string }
                    println("[oml_testmod] reboot trigger: on ${current.javaClass.simpleName}, buttons=$labels")
                }
                if (press(current, RELOAD_BUTTON)) {
                    println("[oml_testmod] reboot trigger: pressed '$RELOAD_BUTTON' on ${current.javaClass.simpleName}")
                    phase = Phase.DONE
                    issued = true
                }
            }
            Phase.DONE -> Unit
        }
    }

    /** Finds the button by the label the player reads and presses it, exactly once. */
    private fun press(screen: Screen, label: String): Boolean {
        val button = screen.children().filterIsInstance<Button>().firstOrNull { it.message.string == label }
            ?: return false
        button.onPress(EMPTY_INPUT)
        return true
    }

    /** [Button.onPress] only reads the modifier state, and no acceptance run presses a modifier. */
    private object EMPTY_INPUT : InputWithModifiers {
        override fun input(): Int = 0
        override fun modifiers(): Int = 0
    }

    private const val MODS_BUTTON = "Mods"
    private const val RELOAD_BUTTON = "Reload Mods"
}
