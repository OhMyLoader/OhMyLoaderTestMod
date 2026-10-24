package oml.testmod

import org.ohmyloader.api26_3.OMLGame

/** A dedicated server never boots a client, so the game's own client instance is the side test. */
internal fun isServerSide(): Boolean = OMLGame.client == null
