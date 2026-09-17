package tv.opentvcast

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import org.hamcrest.Matchers.not
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MainActivityTest — Instrumented tests for MainActivity.
 *
 * WHY: Unit tests cover the overlay decision logic (see OverlayDecisionTest).
 * What must run on a real device/emulator:
 * - Does the Activity start without crashing?
 * - Does the app UI (content container) show at startup?
 * - Is the streaming overlay hidden until a session starts?
 *
 * HOW: instrumented tests — run with `./gradlew connectedAndroidTest`.
 * The [ActivityScenarioRule] starts and stops the Activity for each test.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    // ActivityScenarioRule starts the Activity before each test and stops it after
    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    /**
     * Test: MainActivity starts without crashing.
     *
     * The most basic requirement: if this fails, something is fundamentally
     * broken. ActivityScenarioRule fails the test during setup on any crash.
     */
    @Test
    fun mainActivity_startsWithoutCrash() {
        // If we reach this line, the Activity started without throwing.
    }

    /**
     * Test: the app content container is visible on startup.
     *
     * The old WaitingScreen these tests asserted was dead code (nothing
     * constructed it) and has been removed; the Home fragment now lives in
     * content_container.
     */
    @Test
    fun mainActivity_showsHomeContentOnStart() {
        onView(withId(R.id.content_container))
            .check(matches(isDisplayed()))
    }

    /**
     * Test: the streaming overlay is hidden at startup.
     *
     * If it were visible, a black surface would cover the app UI.
     */
    @Test
    fun mainActivity_streamingContainerHiddenOnStart() {
        onView(withId(R.id.streaming_container))
            .check(matches(not(isDisplayed())))
    }
}
