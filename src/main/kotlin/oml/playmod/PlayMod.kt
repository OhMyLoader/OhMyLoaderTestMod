package oml.playmod

import org.ohmyloader.api.Mod
import org.ohmyloader.api.ModContext
import org.ohmyloader.api.OMLModInitializer
import org.ohmyloader.api.client.OMLKeyBindingProvider
import org.ohmyloader.api.client.OMLKeyBindingRegistry
import org.ohmyloader.api.command.OMLCommandProvider
import org.ohmyloader.api.command.OMLCommandRegistry
import org.ohmyloader.api.content.ContentRegistry
import org.ohmyloader.api.content.OMLContentProvider
import org.ohmyloader.api.network.OMLNetworkProvider
import org.ohmyloader.api.network.OMLNetworkRegistry

/**
 * A mod written the way a player would use one: mine the ore, smelt it, craft a floor, a pad and a waystone,
 * claim the waystone, throw the pearl at it, step onto a pad and get thrown, walk into the kit's own dimension
 * and hear its clock. Everything it does goes through the loader's mod-facing surface — the content declarations,
 * the events, the command tree, the config file, the two network payloads, the key bind and the scheduler — and
 * nothing reaches past them to a game registry of its own.
 *
 * Type it `/kit` in a world with this jar in it.
 */
@Mod(id = "oml_playmod", name = "Playground Kit", version = "0.1.0")
class PlayMod : OMLModInitializer, OMLContentProvider, OMLCommandProvider, OMLNetworkProvider,
    OMLKeyBindingProvider {

    override fun onInitialize(context: ModContext) {
        PlayConfig.load(context)
        PlayEvents.register()
        println(
            "[oml_playmod] ${context.id} ${context.version} ready — ${context.name}; " +
                "pad_charge=${PlayConfig.padCharge}, announce=${PlayConfig.announceJoins}",
        )
    }

    override fun declareContent(registry: ContentRegistry) = PlayContent.declare(registry)

    override fun declareCommands(commands: OMLCommandRegistry) = PlayCommands.declare(commands)

    override fun declareNetwork(network: OMLNetworkRegistry) = PlayNetwork.declare(network)

    override fun declareKeyBindings(keyBindings: OMLKeyBindingRegistry) {
        // The one place a player's own key reaches the server: the panel is answered by the server's read of the
        // machines, not by anything the client can see.
        keyBindings.register("panel", "key.keyboard.j") { PlayNetwork.requestPanel("asked by key") }
    }
}
