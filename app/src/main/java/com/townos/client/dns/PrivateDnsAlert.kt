package com.townos.client.dns

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Getting the Private DNS problem in front of the user, and getting them to the
 * one screen that fixes it.
 *
 * The in-app banner is not enough on its own: the failure shows up when they are
 * in a *browser* wondering why `gitea.default.home` won't load, not while
 * staring at this app. So we also post a system notification while the tunnel is
 * up and Private DNS is strict, and tapping it goes straight to the setting.
 */
object PrivateDnsAlert {

    private const val CHANNEL_ID = "private-dns"
    private const val NOTIFICATION_ID = 1001

    /**
     * The Private DNS settings screen.
     *
     * `android.settings.PRIVATE_DNS_SETTINGS` is the real action, but it is not a
     * public `Settings.ACTION_*` constant on every API level and some OEM ROMs
     * don't register it — so we resolve it first and fall back to the wireless
     * settings screen rather than throwing ActivityNotFoundException in the
     * user's face.
     */
    fun settingsIntent(context: Context): Intent {
        val privateDns = Intent("android.settings.PRIVATE_DNS_SETTINGS")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val resolvable = privateDns.resolveActivity(context.packageManager) != null
        return if (resolvable) {
            privateDns
        } else {
            Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun openSettings(context: Context) {
        context.startActivity(settingsIntent(context))
    }

    /**
     * Post (or clear) the warning notification.
     *
     * Silently does nothing when the notification permission has not been
     * granted — the in-app banner still covers that case, and nagging for a
     * permission we cannot use is worse than the missing notification.
     */
    fun update(context: Context, status: PrivateDnsCheck.Status) {
        val manager = NotificationManagerCompat.from(context)

        if (status !is PrivateDnsCheck.Status.Strict) {
            manager.cancel(NOTIFICATION_ID)
            return
        }

        // The permission check is inlined rather than delegated to canNotify()
        // because lint's MissingPermission check only recognizes a guard it can
        // see in this same function — and it is right to insist: notify() throws
        // SecurityException if we get this wrong.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        ensureChannel(context)

        val tap = android.app.PendingIntent.getActivity(
            context,
            0,
            settingsIntent(context),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Town OS names won't resolve")
            .setContentText("Private DNS is set to ${status.hostname}. Tap to change it.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Android's Private DNS is set to \"${status.hostname}\", so every DNS lookup " +
                        "goes there instead of to your Town OS box. Names like " +
                        "gitea.default.home will not resolve even though the tunnel is up.\n\n" +
                        "Tap to open Private DNS settings and choose Automatic or Off.",
                ),
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setContentIntent(tap)
            .setAutoCancel(false)
            .setOngoing(false)
            .build()

        manager.notify(NOTIFICATION_ID, notification)
    }

    fun clear(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    /** POST_NOTIFICATIONS is runtime-granted from API 33; before that it is implicit. */
    fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "DNS problems",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Warns when an Android setting is preventing Town OS names from resolving."
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }
}
