package oml.playmod

/**
 * What the kit draws over the world: the last thing it did, the panel the server sent, and the counters a player
 * uses to tell whether the pad under their feet is armed. The lines are assembled from state rather than
 * remembered twice, so the overlay cannot disagree with the machine it came from.
 */
object PlayOverlay {

    /** Rows down the top-left corner by default, which is where a player looks for a mod's own readout. */
    fun lines(width: Int, height: Int): List<Triple<Int, Int, String>> {
        val x = if (PlayConfig.overlayAtTop) 4 else width - 240
        val top = if (PlayConfig.overlayAtTop) 4 else height / 2 - 40
        val rows = mutableListOf(
            Triple(x, top, "§eoml_playmod"),
            Triple(x, top + 10, PlayState.overlayLine),
        )
        PlayNetwork.panel?.let { rows += Triple(x, top + 20, it) }
        if (PlayState.lastChat.isNotEmpty()) rows += Triple(x, top + 30, shorten(PlayState.lastChat))
        return rows
    }

    /** A chat line is wide; the overlay shows the head of it and the rest is what the player can scroll for. */
    private fun shorten(text: String): String = if (text.length <= 44) text else text.take(43) + "…"
}
