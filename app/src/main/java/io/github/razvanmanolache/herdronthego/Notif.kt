package io.github.razvanmanolache.herdronthego

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/** Agent-finished pings: one channel, one notification per pane per PC. */
object AgentNotif {
    const val CHANNEL = "agent_done"
    const val EXTRA_SERVER = "io.github.razvanmanolache.herdronthego.SERVER"
    const val EXTRA_PANE = "io.github.razvanmanolache.herdronthego.PANE"
    const val EXTRA_TAB = "io.github.razvanmanolache.herdronthego.TAB"
    const val EXTRA_SESSION = "io.github.razvanmanolache.herdronthego.SESSION"

    private fun manager(ctx: Context): NotificationManager =
        ctx.getSystemService(NotificationManager::class.java)

    private fun ensureChannel(ctx: Context) {
        manager(ctx).createNotificationChannel(
            NotificationChannel(CHANNEL, "Agent updates",
                NotificationManager.IMPORTANCE_DEFAULT))
    }

    /** Server key is part of the id: two PCs can own the same pane id. */
    private fun idFor(srv: ServerConn, paneId: String): Int =
        (srv.conn.key + "\n" + srv.currentSession + "\n" + paneId).hashCode()

    private fun canPost(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ctx.checkSelfPermission(
            android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun notify(ctx: Context, srv: ServerConn, paneId: String, tabId: String,
               tabLabel: String, status: String) {
        if (!canPost(ctx)) return
        ensureChannel(ctx)
        val intent = Intent(ctx, MainActivity::class.java)
            .putExtra(EXTRA_SERVER, srv.conn.key)
            .putExtra(EXTRA_PANE, paneId)
            .putExtra(EXTRA_TAB, tabId)
            .putExtra(EXTRA_SESSION, srv.currentSession)
        val pi = PendingIntent.getActivity(
            ctx, idFor(srv, paneId), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = android.app.Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_status)
            .setContentTitle("Agent $status")
            .setContentText("$tabLabel · " +
                (if (srv.conn.port == 22) srv.conn.host
                 else "${srv.conn.host}:${srv.conn.port}"))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        manager(ctx).notify(idFor(srv, paneId), n)
    }

    fun cancel(ctx: Context, srv: ServerConn, paneId: String) {
        try { manager(ctx).cancel(idFor(srv, paneId)) } catch (_: Exception) {}
    }

    fun cancelServer(ctx: Context, srv: ServerConn) {
        try {
            srv.notified.forEach { manager(ctx).cancel(idFor(srv, it)) }
        } catch (_: Exception) {}
    }
}
