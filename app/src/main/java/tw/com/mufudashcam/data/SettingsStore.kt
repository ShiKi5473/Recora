package tw.com.mufudashcam.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import tw.com.mufudashcam.BuildConfig

data class AppSettings(
    val wifiPassword: String = DashcamConfig.DEFAULT_PASSWORD,
    val ssidPrefix: String = DashcamConfig.DEFAULT_SSID_PREFIX,
    /** 下載時預設一併下載後鏡頭。 */
    val includeRear: Boolean = true,
    /** 僅 debug build 有效：記錄器位址覆寫（例如 127.0.0.1:8080 的假記錄器，搭配 adb reverse），設定後略過 Wi-Fi 綁定。 */
    val hostOverride: String = "",
) {
    val overrideActive: Boolean get() = BuildConfig.DEBUG && hostOverride.isNotBlank()
    val host: String get() = if (overrideActive) hostOverride.trim() else DashcamConfig.DEFAULT_HOST
}

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsStore(context: Context, scope: CoroutineScope) {
    private val store = context.dataStore

    private object Keys {
        val password = stringPreferencesKey("wifi_password")
        val ssidPrefix = stringPreferencesKey("ssid_prefix")
        val includeRear = booleanPreferencesKey("include_rear")
        val hostOverride = stringPreferencesKey("host_override")
    }

    val flow: Flow<AppSettings> = store.data.map(::read)

    val state: StateFlow<AppSettings> = flow.stateIn(scope, SharingStarted.Eagerly, AppSettings())

    suspend fun current(): AppSettings = flow.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs -> write(prefs, transform(read(prefs))) }
    }

    private fun read(p: Preferences) = AppSettings(
        wifiPassword = p[Keys.password] ?: DashcamConfig.DEFAULT_PASSWORD,
        ssidPrefix = p[Keys.ssidPrefix] ?: DashcamConfig.DEFAULT_SSID_PREFIX,
        includeRear = p[Keys.includeRear] ?: true,
        hostOverride = p[Keys.hostOverride] ?: "",
    )

    private fun write(p: MutablePreferences, s: AppSettings) {
        p[Keys.password] = s.wifiPassword
        p[Keys.ssidPrefix] = s.ssidPrefix
        p[Keys.includeRear] = s.includeRear
        p[Keys.hostOverride] = s.hostOverride
    }
}
