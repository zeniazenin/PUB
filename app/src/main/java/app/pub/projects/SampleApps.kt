package app.pub.projects

import android.content.Context
import app.pub.core.ProjectBundle

/**
 * The one sample app a new install starts with: the Tip Calculator package in assets/samples/tip-calculator.json.
 *
 * On first launch, before the library is first listed, [seedIfNeeded] copies it into the library with
 * [ProjectStore.save], so it gets a new UUID and is an ordinary saved app: no special ID, flag, tile or protection.
 * It can be opened, edited and deleted like any other app, and deleting it is permanent.
 *
 * The decision is made once per install and recorded in the host preference [SEEDED_KEY], also when nothing was
 * seeded because the library already had packages. So upgrades and installs that already have apps never get the
 * sample, and a deleted sample never returns. A failure leaves the flag unset (the next launch retries) and never
 * blocks startup: [seedIfNeeded] does not throw and logs nothing.
 */
object SampleApps {
    const val ASSET = "samples/tip-calculator.json"
    /** Host preference ("host" file): true once the first-launch seeding decision has been carried out. */
    const val SEEDED_KEY = "samples.seeded"
    private const val PREFS = "host"
    // Serializes concurrent library loads, so two decisions can never both see an empty library.
    private val lock = Any()

    /** The first-launch decision: seed only if seeding never ran before and the library holds no package at all. */
    fun shouldSeed(alreadySeeded: Boolean, libraryEmpty: Boolean): Boolean = !alreadySeeded && libraryEmpty

    /**
     * Seeds the sample if [shouldSeed] says so and records the decision. Call off the main thread, before listing
     * the library. Returns the saved sample, or null when nothing was seeded (already decided, existing apps, or a
     * failure). Never throws.
     */
    fun seedIfNeeded(context: Context, store: ProjectStore): SavedProject? = synchronized(lock) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val alreadySeeded = prefs.getBoolean(SEEDED_KEY, false)
            if (alreadySeeded) return@runCatching null
            val sample = if (shouldSeed(alreadySeeded, store.isEmpty())) store.save(read(context)) else null
            // If recording fails after a save, the next launch sees a non-empty library and only sets the flag.
            check(prefs.edit().putBoolean(SEEDED_KEY, true).commit()) { "Could not record sample seeding" }
            sample
        }.getOrNull()
    }

    /** Reads and validates the bundled sample package. */
    fun read(context: Context): ProjectBundle =
        ProjectBundle.parse(context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() })
}
