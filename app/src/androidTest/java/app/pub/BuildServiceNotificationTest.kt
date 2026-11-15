package app.pub

import android.app.Notification
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.pub.builder.BuildNotifications
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Builds notifications without posting them, starting a service or making a model request. */
@RunWith(AndroidJUnit4::class)
class BuildServiceNotificationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun progressIsGenericAndCancelActionsAreScopedToOneBuild() {
        val phase = BuildNotifications.phase("Generating account-secret@example.com private prompt", false)
        assertEquals("Building your app…", phase)
        assertFalse(phase.contains("secret"))
        val first = BuildNotifications.ongoing(context, phase, "first-test-build")
        val second = BuildNotifications.ongoing(context, phase, "second-test-build")
        assertEquals(BuildNotifications.ONGOING_CHANNEL, first.channelId)
        assertEquals(Notification.VISIBILITY_PRIVATE, first.visibility)
        assertTrue(first.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals("Cancel", first.actions.single().title.toString())
        assertTrue(first.actions.single().actionIntent.isImmutable)
        assertTrue(first.actions.single().actionIntent.isService)
        assertNotEquals(first.actions.single().actionIntent, second.actions.single().actionIntent)
        assertTrue(first.contentIntent.isActivity)
        assertTrue(first.contentIntent.isImmutable)
    }

    @Test fun everyCompletionOutcomeOpensPubAndDoesNotExposeContent() {
        BuildNotifications.Outcome.entries.forEach { outcome ->
            val notification = BuildNotifications.completion(context, outcome)
            assertEquals(BuildNotifications.RESULT_CHANNEL, notification.channelId)
            assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
            assertTrue(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
            assertEquals(0, notification.flags and Notification.FLAG_ONGOING_EVENT)
            assertTrue(notification.contentIntent.isActivity)
            assertTrue(notification.extras.getCharSequence(Notification.EXTRA_TITLE)!!.isNotBlank())
            assertTrue(notification.extras.getCharSequence(Notification.EXTRA_TEXT)!!.contains("Open PUB"))
        }
    }

    @Test fun headlessCaptureAndRepairHaveAccurateGenericProgress() {
        assertEquals("Rendering the app preview…", BuildNotifications.phase(null, true))
        assertEquals("Improving the app preview…", BuildNotifications.phase("Automatic repair 1", false))
        assertEquals("Checking the app preview…", BuildNotifications.phase("Reviewing initial screenshot", false))
    }

    @Test fun researchAndBuildPhasesUseTheControllerLabels() {
        assertEquals("Researching similar apps…", BuildNotifications.phase(app.pub.builder.BuilderController.RESEARCHING, false))
        assertEquals("Building your app…", BuildNotifications.phase(app.pub.builder.BuilderController.BUILDING, false))
    }

    @Test fun behaviorTestingIsNotShownAsAScreenshotCheck() {
        assertEquals("Testing the app…", BuildNotifications.phase(app.pub.builder.BuilderController.TESTING, false))
        assertEquals("Improving the app preview…", BuildNotifications.phase("Fixing app behavior (attempt 1 of 2)...", false))
        assertEquals("Checking the app preview…", BuildNotifications.phase("Checking screen 1 of 3...", false))
    }
}
