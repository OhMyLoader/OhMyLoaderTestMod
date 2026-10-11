package oml.playmod

/**
 * The config the kit reads at start-up. Values come from `<gameDir>/config/oml_playmod.toml`, which the loader
 * generates from the defaults below, so a player can retune the kit without touching code — and every entry has
 * to be read through the getter, because a missing key is a typo in the file rather than a crash.
 */
object PlayConfig {

    var padCharge: Int = 3
        private set
    var padLaunch: Double = 1.4
        private set
    var padRearmTicks: Int = 40
        private set
    var pearlCostsOne: Boolean = true
        private set
    var announceJoins: Boolean = true
        private set
    var overlayAtTop: Boolean = true
        private set
    var maxFps: Int = 0
        private set
    var galleryMarker: String = "oml_playmod:chime"
        private set

    fun load(context: org.ohmyloader.api.ModContext) {
        val config = context.config
        config.define("pad_charge", 3, "How many launches one pad holds before it has to rearm.")
        config.define("pad_launch", 1.4, "How hard a charged pad throws whoever steps on it.")
        config.define("pad_rearm_ticks", 40, "Ticks a pad spends reabsorbing one charge.")
        config.define("pearl_costs_one", true, "Whether a recall pearl is consumed when it lands.")
        config.define("announce_joins", true, "Whether the kit greets a player into the world.")
        config.define("overlay_at_top", true, "Pin the overlay to the top-left; false moves it to mid-screen.")
        config.define("max_fps", 0, "Cap the frame rate for the kit's own overlay; 0 leaves the game alone.")
        config.define("gallery_marker", "oml_playmod:chime", "The timeline marker the gallery clock parks on; matched as an exact string, so give the full namespaced id — a short name silently never matches.")

        padCharge = config.getInt("pad_charge").coerceIn(0, 16)
        padLaunch = config.getDouble("pad_launch").coerceIn(0.1, 8.0)
        padRearmTicks = config.getInt("pad_rearm_ticks").coerceAtLeast(1)
        pearlCostsOne = config.getBoolean("pearl_costs_one")
        announceJoins = config.getBoolean("announce_joins")
        overlayAtTop = config.getBoolean("overlay_at_top")
        maxFps = config.getInt("max_fps").coerceAtLeast(0)
        galleryMarker = config.getString("gallery_marker")
    }
}
