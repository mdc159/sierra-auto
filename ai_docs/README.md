# Third-party reference docs

External libraries, APIs and tools Sierra depends on. One line each: source, then what it covers.

- https://developer.android.com/training/cars/apps : Android for Cars App Library (templates, POI category, host API levels, CarAudioRecord); app uses `androidx.car.app` 1.7.0
- https://developer.android.com/jetpack/androidx/releases/car-app : Car App Library release notes and versions
- https://developer.android.com/training/cars/testing/dhu : Android Auto Desktop Head Unit, used by `scripts/dhu.ps1`
- https://developer.android.com/jetpack/androidx/releases/security : `androidx.security:security-crypto` (EncryptedSharedPreferences for keys)
- https://square.github.io/okhttp/ : OkHttp 4 HTTP/WebSocket client and MockWebServer used in tests
- https://kotlinlang.org/docs/coroutines-overview.html : Kotlin coroutines (cancellation, structured concurrency)
- https://robolectric.org/ : Robolectric JVM Android tests
- https://shizuku.rikka.app/ : Shizuku API used by the experimental installer path
- https://developers.google.com/android-publisher : Google Play Developer API, used for internal-testing uploads (`.github/workflows/play.yml`)
- https://platform.openai.com/docs/api-reference/chat : OpenAI-compatible chat completions, the protocol Sierra and the bridge speak
- https://platform.openai.com/docs/guides/realtime : OpenAI Realtime API (duplex voice session)
- https://elevenlabs.io/docs : ElevenLabs TTS and Agents (conversational) APIs
- https://docs.litellm.ai/ : LiteLLM gateway that fronts model providers on Studio 54
- https://infisical.com/docs : Infisical vault behind Studio 54 sign-in (`studio54/InfisicalClient.kt`)
- https://nominatim.org/release-docs/latest/api/Overview/ : Nominatim geocoding (`places/NominatimClient.kt`)
- https://wiki.openstreetmap.org/wiki/Overpass_API : Overpass API for nearby places (`places/OverpassClient.kt`)
- https://www.elmelectronics.com/ic/elm327/ : ELM327 OBD interpreter command set (`obd/Elm327Client.kt`)
- https://docs.astral.sh/uv/ : uv, the Python environment/lock tool for `bridge/` and `tools/voice-probe/`
