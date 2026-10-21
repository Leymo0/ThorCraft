package dev.leymo.thorcraft.panel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast

/** Shows Android's "Update this app?" confirmation, and reports failures. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (confirm != null) ctx.startActivity(confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> Toast.makeText(ctx, "ThorCraft Panel updated", Toast.LENGTH_SHORT).show()
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown error"
                val hint = if (msg.contains("signature", true) || msg.contains("conflict", true))
                    " — uninstall the old ThorCraft Panel once, then install this version; future updates will work in-app."
                else ""
                Toast.makeText(ctx, "Update failed: $msg$hint", Toast.LENGTH_LONG).show()
            }
        }
    }
}
