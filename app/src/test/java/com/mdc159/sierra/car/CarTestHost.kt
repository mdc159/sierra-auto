package com.mdc159.sierra.car

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.car.app.HandshakeInfo
import androidx.car.app.OnDoneCallback
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.PlaceListMapTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.serialization.Bundleable
import androidx.car.app.testing.ScreenController
import androidx.car.app.testing.SessionController
import androidx.car.app.testing.TestCarContext
import androidx.car.app.versioning.CarAppApiLevels
import androidx.lifecycle.Lifecycle
import com.mdc159.sierra.agent.AgentConfig
import com.mdc159.sierra.agent.ConfigStore
import org.junit.Assert.fail
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowApplication

/**
 * Helpers for running the real car [Screen]s against the Car App Library's test host
 * ([TestCarContext] + FakeHost) under Robolectric.
 *
 * The test host runs every template validator the real host runs in-process, so a
 * template that would crash the truck throws here instead.
 */
object CarTestHost {

    const val HOST_PACKAGE = "com.google.android.projection.gearhead"

    /** A car context whose handshake reports [apiLevel] (the Sierra's head unit is 5+). */
    fun carContext(apiLevel: Int = CarAppApiLevels.LEVEL_5): TestCarContext {
        val context = TestCarContext.createCarContext(RuntimeEnvironment.getApplication())
        context.updateHandshakeInfo(HandshakeInfo(HOST_PACKAGE, apiLevel))
        return context
    }

    /** A [SierraSession] bound to [carContext], exactly as the host would bind it. */
    fun session(carContext: TestCarContext): SierraSession {
        val session = SierraSession()
        SessionController(session, carContext, Intent())
        return session
    }

    /**
     * Puts the screen on the stack and moves it to [state]. Screens only reach the host on
     * invalidate() once they are STARTED, so most tests resume them.
     */
    fun <S : Screen> show(screen: S, state: Lifecycle.State = Lifecycle.State.RESUMED): ScreenController =
        ScreenController(screen).moveToState(state)

    /**
     * Replaces the encrypted settings store with a plain in-memory one for this test.
     * The Android Keystore does not exist on the JVM, so the real store cannot open here.
     * Passing null leaves the store empty ("not configured").
     */
    fun installConfig(context: Context, config: AgentConfig?) {
        val prefs = context.getSharedPreferences("sierra_test_agent_config", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val field = ConfigStore::class.java.getDeclaredField("prefs").apply { isAccessible = true }
        field.set(ConfigStore, prefs)
        if (config != null) ConfigStore.save(context, config)
    }

    fun grant(vararg permissions: String) {
        shadowApp().grantPermissions(*permissions)
    }

    fun deny(vararg permissions: String) {
        shadowApp().denyPermissions(*permissions)
    }

    fun denyAll() = deny(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.RECORD_AUDIO,
    )

    private fun shadowApp(): ShadowApplication = shadowOf(RuntimeEnvironment.getApplication())

    /** Runs everything queued on the main looper (coroutine resumptions, posted callbacks). */
    fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    /**
     * Pumps the main looper until [condition] holds or [timeoutMs] of wall time passes.
     * Background work (Dispatchers.IO, MockWebServer) runs on real threads, so this sleeps
     * between pumps rather than advancing the fake clock.
     */
    fun await(timeoutMs: Long = 10_000, what: String = "condition", condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idle()
            if (condition()) return
            Thread.sleep(25)
        }
        idle()
        if (!condition()) fail("Timed out after ${timeoutMs}ms waiting for $what")
    }

    /** Simulates the host delivering a tap on [row]. */
    fun click(row: Row) {
        val delegate = row.onClickDelegate ?: throw AssertionError("Row '${row.title}' is not clickable")
        delegate.sendClick(doneCallback)
    }

    /** Simulates the host delivering a tap on [action]. */
    fun click(action: Action) {
        val delegate = action.onClickDelegate ?: throw AssertionError("Action '${action.title}' is not clickable")
        delegate.sendClick(doneCallback)
    }

    /** Simulates the host delivering a tap on a grid [item]. */
    fun click(item: GridItem) {
        val delegate = item.onClickDelegate ?: throw AssertionError("Grid item '${item.title}' is not clickable")
        delegate.sendClick(doneCallback)
    }

    private val doneCallback = object : OnDoneCallback {
        override fun onSuccess(response: Bundleable?) {}
        override fun onFailure(response: Bundleable) {
            fail("Host callback reported failure: $response")
        }
    }

    // Template accessors that read like the screen description.

    fun Template.asList(): ListTemplate = this as ListTemplate
    fun Template.asGrid(): GridTemplate = this as GridTemplate
    fun Template.asPane(): PaneTemplate = this as PaneTemplate
    fun Template.asMap(): PlaceListMapTemplate = this as PlaceListMapTemplate

    val ListTemplate.rows: List<Row> get() = (singleList ?: ItemList.Builder().build()).items.map { it as Row }
    val PlaceListMapTemplate.rows: List<Row> get() = (itemList ?: ItemList.Builder().build()).items.map { it as Row }
    val GridTemplate.gridItems: List<GridItem> get() = (singleList ?: ItemList.Builder().build()).items.map { it as GridItem }
    val GridItem.titleText: String get() = title.toString()
    val Row.titleText: String get() = title.toString()
    val Row.textLines: List<String> get() = texts.map { it.toString() }
}
