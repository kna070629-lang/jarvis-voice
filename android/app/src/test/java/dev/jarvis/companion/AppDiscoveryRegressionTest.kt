package dev.jarvis.companion

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A regression guard for the application discovery.
 *
 * The discovery once used PackageManager.MATCH_DEFAULT_ONLY, which only keeps
 * the activities that also declare CATEGORY_DEFAULT. Most launcher activities
 * do not, so the directory came back empty and every application was
 * reported as missing, so nothing could be opened.
 */
class AppDiscoveryRegressionTest {

    @Test fun theDiscoveryIsNotFilteredByTheDefaultCategory() {
        assertEquals(
            "The discovery must not use MATCH_DEFAULT_ONLY, because most " +
                "launcher activities do not declare CATEGORY_DEFAULT",
            0,
            AppDiscoveryManager.DISCOVERY_FLAGS,
        )
    }

    @Test fun theFlagDoesNotHideAnyApplication() {
        // MATCH_DEFAULT_ONLY is 0x00010000. Any non zero value that contains
        // it would drop the launcher activities.
        val flags = AppDiscoveryManager.DISCOVERY_FLAGS
        assertEquals(0, flags and 0x00010000)
    }
}
