package com.townos.client.dns

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The Private DNS alert: the notification, and the route to the setting.
 *
 * `android.settings.PRIVATE_DNS_SETTINGS` is not a public `Settings.ACTION_*`
 * constant on every API level, and some OEM ROMs do not register it. Throwing
 * ActivityNotFoundException at a user who is *already* stuck — tunnel up, no
 * names resolving — would be the worst possible moment to fail, so the fallback
 * is pinned here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PrivateDnsAlertTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val notifications: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    @Before
    fun grantNotificationPermission() {
        // update() deliberately refuses to post without POST_NOTIFICATIONS (it is
        // runtime-granted from API 33). Grant it so these tests exercise the
        // posting path rather than the silent-skip path — which is covered
        // separately by `no permission means no notification, and no crash`.
        shadowOf(context as android.app.Application)
            .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun `without the notification permission we post nothing and do not crash`() {
        shadowOf(context as android.app.Application)
            .denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)

        PrivateDnsAlert.update(context, PrivateDnsCheck.Status.Strict("dns.google"))

        assertEquals(0, shadowOf(notifications).size())
    }

    @Test
    fun `prefers the Private DNS screen when the ROM registers it`() {
        val component = ComponentName("com.android.settings", "PrivateDns")
        shadowOf(context.packageManager).run {
            addActivityIfNotPresent(component)
            addIntentFilterForActivity(
                component,
                // resolveActivity() implies MATCH_DEFAULT_ONLY, which only matches
                // filters carrying CATEGORY_DEFAULT — as the real Settings app's do.
                IntentFilter("android.settings.PRIVATE_DNS_SETTINGS").apply {
                    addCategory(Intent.CATEGORY_DEFAULT)
                },
            )
        }

        assertEquals(
            "android.settings.PRIVATE_DNS_SETTINGS",
            PrivateDnsAlert.settingsIntent(context).action,
        )
    }

    @Test
    fun `falls back to wireless settings when the action is unresolvable`() {
        // The OEM-ROM case: nothing handles the action. We must not hand back an
        // intent that throws the moment it is started.
        assertEquals(
            Settings.ACTION_WIRELESS_SETTINGS,
            PrivateDnsAlert.settingsIntent(context).action,
        )
    }

    @Test
    fun `the intent can be started from a non-activity context`() {
        // openSettings() is called from the view model, which holds the
        // Application context, and update() wraps it in a PendingIntent. Both
        // require FLAG_ACTIVITY_NEW_TASK or Android throws at launch.
        val intent = PrivateDnsAlert.settingsIntent(context)

        assertTrue(
            "settings intent must carry FLAG_ACTIVITY_NEW_TASK",
            intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0,
        )
    }

    @Test
    fun `an Ok status posts nothing`() {
        PrivateDnsAlert.update(context, PrivateDnsCheck.Status.Ok)

        assertEquals(0, shadowOf(notifications).size())
    }

    @Test
    fun `a strict status posts a notification naming the provider`() {
        PrivateDnsAlert.update(context, PrivateDnsCheck.Status.Strict("dns.google"))

        val shadow = shadowOf(notifications)
        assertEquals(1, shadow.size())

        // Naming the provider is the point: "DNS is broken" is not actionable,
        // "every lookup is going to dns.google" is.
        val notification = shadow.allNotifications.first()
        val text = shadowOf(notification).contentText.toString()
        assertTrue("the notification must name the offending provider", text.contains("dns.google"))
    }

    @Test
    fun `an Ok status clears a previously posted notification`() {
        // The user fixes the setting while the tunnel is up; the warning must go
        // away on its own rather than lingering as a lie.
        PrivateDnsAlert.update(context, PrivateDnsCheck.Status.Strict("dns.google"))
        PrivateDnsAlert.update(context, PrivateDnsCheck.Status.Ok)

        assertEquals(0, shadowOf(notifications).size())
    }

    @Test
    fun `clear removes a posted notification`() {
        PrivateDnsAlert.update(context, PrivateDnsCheck.Status.Strict("dns.google"))
        PrivateDnsAlert.clear(context)

        assertEquals(0, shadowOf(notifications).size())
    }

    @Test
    fun `re-posting the same warning does not stack duplicates`() {
        repeat(3) {
            PrivateDnsAlert.update(context, PrivateDnsCheck.Status.Strict("dns.google"))
        }

        assertEquals(1, shadowOf(notifications).size())
    }
}
