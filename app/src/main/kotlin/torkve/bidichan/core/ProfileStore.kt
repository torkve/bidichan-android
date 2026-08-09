package torkve.bidichan.core

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import java.io.File

/**
 * Loads and persists the profile list in the app's private storage.
 *
 * Decoding is deliberately forgiving. A profile written by an older build is
 * missing whatever fields we have added since, and an all-or-nothing decode
 * would throw the whole list away — so unknown keys are ignored, missing ones
 * take their defaults, and a single unreadable entry is dropped rather than
 * costing the user every other profile.
 */
class ProfileStore(context: Context) {
    private val file = File(context.filesDir, "profiles.json")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    @Volatile
    var profiles: List<Profile> = emptyList()
        private set

    init {
        load()
    }

    fun load() {
        if (!file.exists()) {
            profiles = emptyList()
            return
        }
        val text = runCatching { file.readText() }.getOrElse {
            Log.w(TAG, "could not read the profile list", it)
            profiles = emptyList()
            return
        }
        runCatching { json.decodeFromString<List<Profile>>(text) }
            .onSuccess { profiles = it; return }
        // The list as a whole did not decode. Keep the original bytes for
        // recovery, then salvage whatever entries still parse.
        runCatching { file.copyTo(File(file.path + ".bak"), overwrite = true) }
        profiles = salvage(text)
        Log.w(TAG, "profile list was damaged; salvaged ${profiles.size} entries")
    }

    /** Decodes entry by entry, so one bad profile costs only itself. */
    private fun salvage(text: String): List<Profile> {
        val tree = runCatching { json.parseToJsonElement(text) }.getOrNull()
        if (tree !is JsonArray) return emptyList()
        return tree.mapNotNull { element ->
            runCatching { json.decodeFromJsonElement(Profile.serializer(), element) }.getOrNull()
        }
    }

    private fun persist() {
        runCatching { file.writeText(json.encodeToString(profiles)) }
            .onFailure { Log.e(TAG, "could not save the profile list", it) }
    }

    fun upsert(profile: Profile) {
        val next = profiles.toMutableList()
        val idx = next.indexOfFirst { it.id == profile.id }
        if (idx >= 0) next[idx] = profile else next.add(profile)
        profiles = next
        persist()
    }

    fun delete(profile: Profile, secrets: Secrets) {
        profiles = profiles.filterNot { it.id == profile.id }
        secrets.remove(profile.pskAccount)
        persist()
    }

    fun byId(id: String?): Profile? = profiles.firstOrNull { it.id == id }

    private companion object {
        const val TAG = "ProfileStore"
    }
}
