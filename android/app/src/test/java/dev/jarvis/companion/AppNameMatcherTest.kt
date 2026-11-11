package dev.jarvis.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the application name matching.
 *
 * This is the code that decides whether an application opens, so the rules
 * are checked here rather than only on the phone.
 */
class AppNameMatcherTest {

    @Test fun theNameIsNormalisedTheWayTheRecogniserWritesIt() {
        assertEquals("whatsapp", AppNameMatcher.normalize("WhatsApp"))
        assertEquals("whats app", AppNameMatcher.normalize("Whats App"))
        assertEquals("youtube", AppNameMatcher.normalize("YouTube"))
        assertEquals("truecaller", AppNameMatcher.normalize("Truecaller"))
    }

    @Test fun anExactNameScoresTheHighest() {
        val score = AppNameMatcher.score("whatsapp", listOf("WhatsApp", "wa"))
        assertTrue(score != null && score!! >= 100)
    }

    @Test fun theNamesPeopleSayAreFound() {
        assertTrue(AppNameMatcher.score("wa", listOf("WhatsApp", "wa")) != null)
        assertTrue(AppNameMatcher.score("insta gram", listOf("Instagram", "insta")) != null)
        assertTrue(AppNameMatcher.score("you tube", listOf("YouTube")) != null)
        assertTrue(AppNameMatcher.score("tg", listOf("Telegram", "tele gram")) != null)
    }

    @Test fun aSmallMistakeIsStillFound() {
        // A recogniser sometimes writes a name with one wrong letter.
        assertTrue(AppNameMatcher.score("whatsap", listOf("WhatsApp")) != null)
        assertTrue(AppNameMatcher.score("telegram", listOf("Telegram")) != null)
    }

    @Test fun anUnrelatedNameIsNotACandidate() {
        assertNull(AppNameMatcher.score("piano lessons", listOf("WhatsApp", "Spotify")))
    }

    @Test fun thePackageNameIsUnambiguous() {
        // The discovery checks the package name exactly before it scores, and
        // the scoring still finds it.
        assertTrue(AppNameMatcher.score("com.whatsapp", listOf("com.whatsapp")) != null)
    }

    @Test fun theDistanceCountsTheEdits() {
        assertEquals(0, AppNameMatcher.levenshtein("jarvis", "jarvis"))
        assertEquals(2, AppNameMatcher.levenshtein("jarvis", "jarvsi"))
        assertEquals(2, AppNameMatcher.levenshtein("jarvis", "jarvisxx"))
        assertEquals(6, AppNameMatcher.levenshtein("jarvis", "kettle"))
    }
}

/**
 * Tests for the launcher.
 *
 * An application is opened with its launch intent, because an application
 * only has to publish one launcher activity. Forcing a web address onto a
 * package is what stopped the applications from opening.
 */
class AppLauncherTest {

    @Test fun aLauncherWithoutAPhoneCannotOpenAnything() {
        // Without a phone there is nothing to start, and the launcher says so
        // instead of claiming success.
        val launcher = AppLauncher(null)
        assertFalse(launcher.isInstalled("com.whatsapp"))
    }

    @Test fun everyIntegrationDeclaresAnOpenAction() {
        // Every supported application must be openable, otherwise it cannot
        // even be launched by name.
        for (integration in AppIntegrationEngine(null).all()) {
            assertTrue(
                "${integration.label} has no open action",
                integration.action("open") != null,
            )
        }
    }

    @Test fun theSupportedApplicationsAreTheOnesThatWereChecked() {
        val packages = AppIntegrationEngine(null).all().map { it.packageName }
        for (expected in listOf(
            "com.whatsapp",
            "org.telegram.messenger",
            "com.instagram.android",
            "com.spotify.music",
            "com.google.android.youtube",
            "com.canva.editor",
            "com.github.android",
            "com.openai.chatgpt",
            "com.anthropic.claude",
        )) {
            assertTrue("$expected is missing", packages.contains(expected))
        }
    }
}
