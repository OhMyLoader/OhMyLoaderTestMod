package oml.playmod

import net.minecraft.commands.CommandSourceStack
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import org.ohmyloader.api.command.OMLArgumentType
import org.ohmyloader.api.command.OMLCommandRegistry
import org.ohmyloader.api.command.OMLCommandSource
import org.ohmyloader.api26_3.addAttributeModifier
import org.ohmyloader.api26_3.applyEffect
import org.ohmyloader.api26_3.applyTicks
import org.ohmyloader.api26_3.attributeBase
import org.ohmyloader.api26_3.attributeValue
import org.ohmyloader.api26_3.blockDataAt
import org.ohmyloader.api26_3.entitiesOfType
import org.ohmyloader.api26_3.setAttributeBase
import org.ohmyloader.api26_3.playSoundAt
import org.ohmyloader.api26_3.blockIdAt
import org.ohmyloader.api26_3.dimensionId
import org.ohmyloader.api26_3.registry
import org.ohmyloader.api26_3.setWorldSpawn
import org.ohmyloader.api26_3.tagsOf
import org.ohmyloader.api26_3.clearRespawnAnchor
import org.ohmyloader.api26_3.clockById
import org.ohmyloader.api26_3.clockIds
import org.ohmyloader.api26_3.contains
import org.ohmyloader.api26_3.countOf
import org.ohmyloader.api26_3.dimensionIds
import org.ohmyloader.api26_3.dimensionTypeIds
import org.ohmyloader.api26_3.equip
import org.ohmyloader.api26_3.ignite
import org.ohmyloader.api26_3.itemIn
import org.ohmyloader.api26_3.gameModeName
import org.ohmyloader.api26_3.gameRuleValue
import org.ohmyloader.api26_3.giveItem
import org.ohmyloader.api26_3.grantPermissionNode
import org.ohmyloader.api26_3.hasPermissionNode
import org.ohmyloader.api26_3.permissionNodeOverrides
import org.ohmyloader.api26_3.hurt
import org.ohmyloader.api26_3.instanceOf
import org.ohmyloader.api26_3.isForceLoaded
import org.ohmyloader.api26_3.itemId
import org.ohmyloader.api26_3.membersOf
import org.ohmyloader.api26_3.onlinePlayers
import org.ohmyloader.api26_3.revokePermissionNode
import org.ohmyloader.api26_3.openedMenuId
import org.ohmyloader.api26_3.permissionLevel
import org.ohmyloader.api26_3.playSound
import org.ohmyloader.api26_3.remove
import org.ohmyloader.api26_3.respawnAnchor
import org.ohmyloader.api26_3.setGameRule
import org.ohmyloader.api26_3.setRespawnAnchor
import org.ohmyloader.api26_3.setWeather
import org.ohmyloader.api26_3.spawnDustParticles
import org.ohmyloader.api26_3.spawnItemParticles
import org.ohmyloader.api26_3.spawnLivingEntity
import org.ohmyloader.api26_3.teleport
import org.ohmyloader.api26_3.timelineIds
import org.ohmyloader.api26_3.unequip
import org.ohmyloader.api26_3.worldSpawn
import kotlin.math.abs

/**
 * The whole of what a player can ask the kit for. One root, `/kit`, whose leaves each exercise a different part
 * of the API — the point is not the command count but that every action a player takes goes through the same
 * surface a mod author would write against.
 */
object PlayCommands {

    /** Commands run while a server is up, so the lookups below never have to carry a null check. */
    private val server: net.minecraft.server.MinecraftServer
        get() = requireNotNull(PlayLookups.server) { "the kit needs a running server" }

    /** The node `/kit zap` needs, granted on join by [PlayEvents] and revocable again. */
    const val KEEPER_NODE = "oml_playmod:keeper"

    fun declare(commands: OMLCommandRegistry) {
        commands.register("kit") {
            executes { source -> source.reply(help(source)) }

            subcommand("give") { executes { withPlayer(it) { p -> giveKit(p) } } }
            subcommand("home") {
                executes { withPlayer(it) { p -> homeStatus(p) } }
                subcommand("clear") { executes { withPlayer(it) { p -> clearHome(p) } } }
            }
            subcommand("pearl") { executes { withPlayer(it) { p -> recall(p) } } }
            // The command form of right-clicking the stone, for a player who arrived by teleport and so is
            // standing on the pad rather than looking at the waystone.
            subcommand("claim") { executes { withPlayer(it) { p -> claimStone(p) } } }
            subcommand("pad") {
                executes { withPlayer(it) { p -> padStatus(p) } }
                subcommand("charge") { executes { withPlayer(it) { p -> chargePad(p) } } }
                argument("steps", OMLArgumentType.INTEGER) {
                    executes { source ->
                        val who = require(source) ?: return@executes
                        source.reply(chargePad(who, source.getInt("steps")))
                    }
                }
            }
            subcommand("gallery") {
                subcommand("build") { executes { withPlayer(it) { p -> PlayGallery.build() } } }
                subcommand("enter") { executes { withPlayer(it) { p -> PlayGallery.enter(p) } } }
                subcommand("leave") { executes { withPlayer(it) { p -> PlayGallery.leave(p) } } }
                executes { withPlayer(it) { p -> PlayGallery.status() } }
            }
            subcommand("clock") {
                executes { withPlayer(it) { p -> clockStatus(p) } }
                argument("seconds", OMLArgumentType.INTEGER) {
                    executes { source ->
                        val who = require(source) ?: return@executes
                        source.reply(clockTo(who, source.getInt("seconds")))
                    }
                }
            }
            subcommand("zap") {
                // The kit's own node, granted on join by PlayEvents: a fine-grained gate that does not need OP.
                permissionNode = KEEPER_NODE
                argument("target", OMLArgumentType.WORD) { executes { source -> source.reply(zap(source)) } }
            }
            subcommand("mob") {
                permissionNode = KEEPER_NODE
                argument("type", OMLArgumentType.WORD) {
                    argument("count", OMLArgumentType.INTEGER) { executes { source -> source.reply(summon(source)) } }
                }
            }
            subcommand("weather") {
                permissionNode = KEEPER_NODE
                argument("kind", OMLArgumentType.WORD) { executes { source -> source.reply(weather(source)) } }
            }
            subcommand("rule") {
                permissionNode = KEEPER_NODE
                argument("name", OMLArgumentType.WORD) {
                    argument("value", OMLArgumentType.GREEDY_STRING) { executes { source -> source.reply(rule(source)) } }
                }
            }
            subcommand("wear") { executes { withPlayer(it) { p -> wearKitArmour(p) } } }
            subcommand("probe") { executes { withPlayer(it) { p -> moveProbe(p) } } }
            // The creative book is the one screen a mod's own content has to reach, so the kit can put the
            // player in creative and ask what the game's tab registry actually holds.
            subcommand("creative") { executes { withPlayer(it) { p -> goCreative(p) } } }
            subcommand("tabs") { executes { source -> source.reply(tabRow()) } }
            // The kit's own node, spoken for out loud: `/kit keeper off` takes the gate away again, which is
            // the same lever `/kit zap` refuses on without it.
            subcommand("keeper") {
                argument("hold", OMLArgumentType.BOOLEAN) { executes { source -> source.reply(keeper(source)) } }
                executes { source -> source.reply(keeperStatus(source)) }
            }
            // Deliberately the game's own ladder rather than the kit's node: an operator-only leaf, so a
            // non-op player sees the gate refuse — which is the honest demonstration of permissionLevel.
            subcommand("admin") {
                permissionLevel = 2
                executes { source -> source.reply(adminStatus(source)) }
            }
            subcommand("tag") {
                argument("tag", OMLArgumentType.GREEDY_STRING) { executes { source -> source.reply(tagReport(source)) } }
                executes { source -> source.reply(tagSummary()) }
            }
            subcommand("panel") {
                argument("visible", OMLArgumentType.BOOLEAN) { executes { source -> source.reply(panel(source)) } }
                executes { source -> source.reply(tagToggle(source)) }
            }
            subcommand("anchor") {
                executes { withPlayer(it) { p -> anchorStatus(p) } }
                subcommand("here") { executes { withPlayer(it) { p -> anchorHere(p) } } }
                subcommand("clear") { executes { withPlayer(it) { p -> clearAnchor(p) } } }
                subcommand("world") { executes { withPlayer(it) { p -> worldSpawnStatus(p) } } }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The actions behind the leaves
    // ---------------------------------------------------------------------------------------------

    private fun giveKit(player: ServerPlayer): String {
        val sword = player.giveItem(PlayContent.id(PlayContent.SWORD), 1)
        val pearl = player.giveItem(PlayContent.id(PlayContent.PEARL), 2)
        val key = player.giveItem(PlayContent.id(PlayContent.KEY), 1)
        val ingot = player.giveItem(PlayContent.id(PlayContent.INGOT), 8)
        val held = player.itemIn("mainhand")?.itemId ?: "empty hand"
        val count = player.inventory.countOf(PlayContent.id(PlayContent.INGOT))
        return "sword=$sword pearl=$pearl key=$key ingot=$ingot; holding $held, $count ingots in the pack"
    }

    private fun wearKitArmour(player: ServerPlayer): String {
        val before = player.itemIn("feet")?.itemId ?: "nothing"
        val put = player.equip("feet", "minecraft:leather_boots", 1)
        val after = player.itemIn("feet")?.itemId ?: "nothing"
        val taken = player.unequip("feet")
        return "feet was $before, boots equipped ${put != null}, now $after, unequipped ${taken?.itemId ?: "nothing"}"
    }

    private fun homeStatus(player: ServerPlayer): String {
        val home = PlayState.homeOf(player)
        return if (home == null) {
            "no waystone claimed — right-click one, or /kit anchor here"
        } else {
            "home is ${home.second} in ${home.first}; activations ${PlayState.activationsOf(player)}"
        }
    }

    private fun clearHome(player: ServerPlayer): String {
        PlayState.clearHome(player)
        return "home forgotten"
    }

    /** The pearl: it sends the thrower to the waystone they claimed, and costs one pearl if the config says so. */
    fun recall(player: ServerPlayer): String {
        val home = PlayState.homeOf(player)
            ?: return "no waystone claimed yet"
        val had = player.inventory.remove(PlayContent.id(PlayContent.PEARL), 1)
        val destination = home.second
        val moved = player.teleport(
            destination.x + 0.5, destination.y.toDouble(), destination.z + 0.5, home.first,
        )
        if (moved) {
            (player.level() as? ServerLevel)?.playSoundAt(
                "minecraft:entity.enderman.teleport", player.x, player.y, player.z,
            )
        }
        return "pearl flew ${if (moved) "home" else "nowhere"}; $had consumed, " +
            "${player.inventory.countOf(PlayContent.id(PlayContent.PEARL))} left; " +
            PlayLookups.level(home.first)?.let { target ->
                "from ${player.dimensionId} ${player.blockPosition()} to ${target.dimensionId} $destination " +
                    "loaded=${target.isLoaded(destination)} forced=${target.isForceLoaded(destination)} " +
                    "border=${target.worldBorder.isWithinBounds(destination)} same=${target == player.level()}"
            } ?: "the home's dimension is not loaded on this server"
    }

    fun enterGallery(player: ServerPlayer): String = PlayGallery.enter(player)

    /** The waystone under your feet, or the gallery's when you are standing on the pad beside it. */
    private fun claimStone(player: ServerPlayer): String {
        val level = player.level() as? ServerLevel ?: return "no server level under you"
        val below = player.blockPosition().below()
        val stone = if (level.blockIdAt(below) == PlayMachines.WAYSTONE) below else PlayGallery.WAYSTONE_AT
        if (level.blockIdAt(stone) != PlayMachines.WAYSTONE) return "no waystone here to claim"
        val here = player.blockPosition()
        if (maxOf(abs(stone.x - here.x), abs(stone.y - here.y), abs(stone.z - here.z)) > 3) {
            return "that waystone is out of reach"
        }
        val anchored = PlayMachines.claimWaystone(level, stone, player)
        val home = PlayState.homeOf(player) ?: return "claimed $stone, but the kit kept no home"
        return "claimed $stone; home is ${home.second} in ${home.first}, anchor accepted=$anchored"
    }

    private fun padStatus(player: ServerPlayer): String {
        val machine = PlayMachines.machineUnder(player) ?: return "not standing on a kit block"
        val (level, position) = machine
        val data = level.blockDataAt(position) ?: return "no data at $position"
        return "standing on ${level.blockIdAt(position)}; charge=${data.getInt("charge")}, " +
            "launches=${data.getInt("launches")}, pulses=${data.getInt("pulses")}, " +
            "fired here=${PlayState.padsFired[PlayState.padKey(level.dimensionId, position)] ?: 0}"
    }

    private fun chargePad(player: ServerPlayer, steps: Int = 1): String {
        val level = player.level() as? ServerLevel ?: return "not on a server level"
        val position = player.blockPosition().below()
        val data = level.blockDataAt(position) ?: return "nothing under your feet to charge"
        if (!PlayMachines.padAt(level, position)) return "${level.blockIdAt(position)} is not a pad"
        val charge = (data.getInt("charge") + steps).coerceIn(0, PlayConfig.padCharge)
        data.putInt("charge", charge)
        return "pad charged to $charge/${PlayConfig.padCharge} (config pad_charge)"
    }

    private fun clockStatus(player: ServerPlayer): String =
        PlayGallery.status() + "; clocks=${server.clockIds.sorted()}, timelines=${server.timelineIds.sorted()}, " +
            "dimensions=${server.dimensionIds.sorted()}"

    private fun clockTo(player: ServerPlayer, seconds: Int): String {
        val level = player.level() as? ServerLevel ?: return "not on a server level"
        val clock = server.clockById(PlayContent.id(PlayContent.CLOCK)) ?: return "no gallery clock"
        val manager = level.clockManager()
        val instance = manager.instanceOf(clock) ?: return "the gallery clock runs on no instance"
        val before = instance.totalTicks()
        val wrote = manager.applyTicks(clock, seconds)
        return "gallery clock $before -> ${manager.instanceOf(clock)?.totalTicks()}: wrote=$wrote, " +
            "rate=${manager.instanceOf(clock)?.rate()}, paused=${manager.instanceOf(clock)?.isPaused()}"
    }

    private fun zap(source: OMLCommandSource): String {
        val player = require(source) ?: return "no player"
        val level = player.level() as? ServerLevel ?: return "not on a server level"
        val name = source.getString("target")
        val target = level.entitiesOfType("minecraft:$name").firstOrNull() as? net.minecraft.world.entity.LivingEntity
            ?: return "no living $name near ${level.dimensionId}"
        val hurt = target.hurt(PlayContent.id(PlayContent.DAMAGE_TYPE), 3.0f, player)
        val shocked = (source.platform as? CommandSourceStack)?.hasPermissionNode(KEEPER_NODE) == true
        level.spawnDustParticles(0xEAE86D, 1.2f, target.x, target.y + 0.4, target.z, 12)
        if (hurt) player.playSound("minecraft:entity.lightning_bolt.thunder", 0.8f, 1.1f)
        return "zapped $name=$hurt (health ${target.health}/${target.maxHealth}, node held=$shocked)"
    }

    private fun summon(source: OMLCommandSource): String {
        val player = require(source) ?: return "no player"
        val level = player.level() as? ServerLevel ?: return "not on a server level"
        val type = source.getString("type")
        val count = source.getInt("count").coerceIn(1, 8)
        val spawned = (1..count).mapNotNull {
            level.spawnLivingEntity("minecraft:$type", player.x + it, player.y + 1.0, player.z + it)
        }
        val first = spawned.firstOrNull() ?: return "nothing of type $type could be placed"
        first.ignite(60)
        first.applyEffect("minecraft:speed", 200, 1)
        val boosted = first.addAttributeModifier(
            "minecraft:max_health", "oml_playmod:summon_boost", 6.0, "add_value",
        )
        val before = first.attributeBase("minecraft:attack_damage")
        val raised = first.setAttributeBase("minecraft:attack_damage", (before ?: 1.0) + 1.0)
        level.spawnItemParticles(PlayContent.id(PlayContent.INGOT), first.x, first.y + 0.5, first.z, 8)
        return "$type x${spawned.size} at ${player.blockPosition()}; on fire, speed, modifier=$boosted, " +
            "attack $before -> ${first.attributeValue("minecraft:attack_damage")} (raised=$raised)"
    }

    private fun weather(source: OMLCommandSource): String {
        val player = require(source) ?: return "no player"
        val level = player.level() as? ServerLevel ?: return "not on a server level"
        return when (source.getString("kind")) {
            "clear" -> described(level, level.setWeather(false, false, 1200), "clear")
            "rain" -> described(level, level.setWeather(true, false, 1200), "rain")
            "storm" -> described(level, level.setWeather(true, true, 1200), "storm")
            else -> "kind must be clear, rain or storm"
        }
    }

    private fun described(level: ServerLevel, wrote: Boolean, kind: String): String =
        "$kind asked for (wrote=$wrote); now ${level.isRaining()}, thunder ${level.isThundering()} " +
            "over ${level.dimensionId}"

    private fun rule(source: OMLCommandSource): String {
        val player = require(source) ?: return "no player"
        val name = source.getString("name")
        val id = if (name.contains(':')) name else "minecraft:$name"
        val was = server.gameRuleValue(id) ?: return "no game rule $id"
        val wrote = server.setGameRule(id, source.getString("value"))
        val now = server.gameRuleValue(id)
        return "$id was $was, wrote=$wrote, now $now"
    }

    /** A group lives in exactly one registry, so the answer names the registries it looked in. */
    private fun tagReport(source: OMLCommandSource): String {
        val name = source.getString("tag")
        val id = if (name.contains(':')) name else "minecraft:$name"
        val blocks = server.registry(net.minecraft.core.registries.Registries.BLOCK)
            ?.membersOf(net.minecraft.core.registries.Registries.BLOCK, id)
        val items = server.registry(net.minecraft.core.registries.Registries.ITEM)
            ?.membersOf(net.minecraft.core.registries.Registries.ITEM, id)
        val damageTypes = server.registry(net.minecraft.core.registries.Registries.DAMAGE_TYPE)
            ?.membersOf(net.minecraft.core.registries.Registries.DAMAGE_TYPE, id)
        val found = listOfNotNull(
            blocks?.takeIf { it.isNotEmpty() }?.let { "block=${it.size} ${it.sorted().take(4)}" },
            items?.takeIf { it.isNotEmpty() }?.let { "item=${it.size} ${it.sorted().take(4)}" },
            damageTypes?.takeIf { it.isNotEmpty() }?.let { "damage_type=${it.size} ${it.sorted().take(4)}" },
        )
        return if (found.isEmpty()) "$id holds nothing in block, item or damage type" else found.joinToString(", ")
    }

    private fun tagSummary(): String {
        val registry = net.minecraft.core.registries.Registries.DAMAGE_TYPE
        val damageTypes = PlayLookups.server?.registry(registry) ?: return "no damage type registry"
        val lightning = damageTypes.membersOf(registry, "minecraft:is_lightning")
        val mine = damageTypes.tagsOf(PlayContent.id(PlayContent.DAMAGE_TYPE))
        return "is_lightning=${lightning.sorted()}, our damage type carries=$mine, " +
            "registered=${damageTypes.contains(PlayContent.id(PlayContent.DAMAGE_TYPE))}"
    }

    private fun anchorStatus(player: ServerPlayer): String {
        val anchor = player.respawnAnchor
        return if (anchor == null) {
            "no anchor: you respawn at the world spawn ${worldSpawnPosition()}"
        } else {
            "anchor at ${anchor.pos()} in ${anchor.globalPos().dimension().identifier()}, yaw ${anchor.yaw()}"
        }
    }

    private fun anchorHere(player: ServerPlayer): String {
        val level = player.level() as? ServerLevel ?: return "not on a server level"
        val wrote = player.setRespawnAnchor(level.dimensionId, player.blockPosition(), player.yRot, player.xRot)
        PlayState.setHome(player, level.dimensionId, player.blockPosition())
        return "anchor written=$wrote at ${player.blockPosition()} in ${level.dimensionId}; " +
            "block there is ${level.blockIdAt(player.blockPosition())}"
    }

    private fun clearAnchor(player: ServerPlayer): String {
        val cleared = player.clearRespawnAnchor()
        PlayState.clearHome(player)
        return "anchor cleared=$cleared, now ${player.respawnAnchor?.pos() ?: "none"}"
    }

    private fun worldSpawnStatus(player: ServerPlayer): String {
        val spawn = server.worldSpawn
        val wrote = server.setWorldSpawn(
            server.dimensionIds.first(), spawn.pos(), spawn.yaw(), spawn.pitch(),
        )
        return "world spawn is ${spawn.pos()} in ${spawn.globalPos().dimension().identifier()}, " +
            "written-back=$wrote, dimension types=${server.dimensionTypeIds.size}"
    }

    private fun panel(source: OMLCommandSource): String {
        val visible = source.getBoolean("visible")
        PlayState.overlayVisible = visible
        return "overlay visible=$visible"
    }

    private fun tagToggle(source: OMLCommandSource): String {
        val before = PlayState.overlayVisible
        PlayState.overlayVisible = !before
        return "overlay shown=$before -> ${!before}; press the panel key for a live readout"
    }

    /** A leaf that needs a player: the console gets a refusal instead of a stack trace. */
    private fun withPlayer(source: OMLCommandSource, body: (ServerPlayer) -> String) {
        val player = require(source)
        if (player == null) {
            source.replyError("this leaf needs a player in the world")
        } else {
            source.reply(body(player))
        }
    }

    /** The server's live instance for whoever sent this, not whichever handle the game happens to carry in the
     * source: after a death the game replaces the instance, and a removed one answers nothing. */
    private fun require(source: OMLCommandSource): ServerPlayer? =
        (source.platform as? CommandSourceStack)?.player?.let { server.getPlayerList().getPlayer(it.uuid) }

    private fun worldSpawnPosition(): String = server.worldSpawn.pos().toString()

    /** The creative book is the screen mod content has to reach, so the kit can put the player in it. */
    private fun goCreative(player: ServerPlayer): String {
        player.setGameMode(net.minecraft.world.level.GameType.CREATIVE)
        return "game mode ${player.gameModeName}; E opens the creative book"
    }

    /** The tab layout the creative screen renders from: every tab's row, column and alignment. */
    private fun tabRow(): String {
        val tabs = net.minecraft.core.registries.BuiltInRegistries.CREATIVE_MODE_TAB
        val rows = tabs.entrySet().mapNotNull { entry ->
            entry.value?.let {
                val right = if (it.isAlignedRight) "R" else ""
                "${entry.key.identifier()}=${it.row()}/${it.column()}$right"
            }
        }.sorted()
        return "${rows.size} tabs: ${rows.joinToString()}"
    }

    /** Three hops: the same call spelled three ways, and the player is left where they started. */
    private fun moveProbe(player: ServerPlayer): String {
        val at = player.blockPosition()
        val up = player.teleport(at.x + 0.5, at.y + 1.1, at.z + 0.5)
        val named = player.teleport(at.x + 0.5, at.y + 2.1, at.z + 0.5, player.dimensionId)
        val down = player.teleport(at.x + 0.5, at.y.toDouble(), at.z + 0.5)
        return "up=$up, named=$named, back=$down; standing at ${player.blockPosition()} in ${player.dimensionId}"
    }

    private fun keeper(source: OMLCommandSource): String {
        val who = require(source) ?: return "no player"
        val hold = source.getBoolean("hold")
        val changed = if (hold) who.grantPermissionNode(KEEPER_NODE) else who.revokePermissionNode(KEEPER_NODE)
        return "keeper node ${if (hold) "granted" else "revoked"} (changed=$changed), " +
            "overrides now ${who.permissionNodeOverrides}"
    }

    private fun keeperStatus(source: OMLCommandSource): String {
        val who = require(source) ?: return "no player"
        return "keeper held=${who.hasPermissionNode(KEEPER_NODE)}, overrides ${who.permissionNodeOverrides}, " +
            "command level ${who.permissionLevel}"
    }

    private fun adminStatus(source: OMLCommandSource): String {
        val who = require(source) ?: return "operator readout needs a player"
        return "level ${who.permissionLevel}, overrides ${who.permissionNodeOverrides}, " +
            "kit node held=${who.hasPermissionNode(KEEPER_NODE)}, servers=${server.onlinePlayers.size} online"
    }

    private fun help(source: OMLCommandSource): String = buildString {
        append("kit: give | home [clear] | pearl | claim | pad [charge|<steps>] | ")
        append("gallery [build|enter|leave] | clock [<seconds>] | zap <type> | mob <type> <count> | ")
        append("weather <clear|rain|storm> | rule <name> <value> | wear | probe | creative | tabs | keeper [<bool>] | ")
        append("tag [<tag>] | panel [<bool>] | anchor [here|clear|world]. ")
        append("You are ${source.name}, game mode ${PlayLookups.playerByName(source.name)?.gameModeName}, ")
        append("level ${PlayLookups.playerByName(source.name)?.permissionLevel ?: -1}, ")
        append("menu ${PlayLookups.playerByName(source.name)?.openedMenuId ?: "none"}, ")
        append("online ${PlayLookups.server?.onlinePlayers?.size ?: 0}.")
    }

    /** The block entity data a broken block used to hold is forgotten with it. */
    fun forgetHomeAt(position: net.minecraft.core.BlockPos) {
        PlayLookups.players.filter { PlayState.homeOf(it)?.second == position }.forEach { PlayState.clearHome(it) }
    }
}
