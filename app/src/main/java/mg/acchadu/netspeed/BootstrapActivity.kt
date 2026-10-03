package mg.acchadu.netspeed

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/**
 * Seul composant "visible" de l'app, et uniquement pour:
 *  1. obtenir POST_NOTIFICATIONS (Android 13+) : les valeurs sont affichees en barre d'etat
 *     via des icones de notification, sans cette permission rien n'apparait,
 *  2. afficher la modale de confirmation,
 *  3. se terminer.
 * Aucun ecran, aucune navigation.
 */
class BootstrapActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            return
        }
        launchOrExplain()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIF) launchOrExplain()
    }

    private fun launchOrExplain() {
        // Couvre aussi Android 8-12, ou les notifications peuvent etre coupees au niveau de l'app.
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            BandwidthService.start(this)
            dialog("NetSpeed", "Le debit reseau et la RAM sont maintenant affiches dans la barre d'etat.")
        } else {
            AlertDialog.Builder(this)
                .setTitle("Notifications desactivees")
                .setMessage("Les valeurs sont affichees sous forme d'icones de notification dans la barre d'etat. Sans les notifications, rien ne peut etre affiche.")
                .setCancelable(false)
                .setPositiveButton("Reglages") { _, _ ->
                    startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    )
                    finish()
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
                .setOnDismissListener { finish() }
                .show()
        }
    }

    private fun dialog(title: String, msg: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg)
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
            .setOnDismissListener { finish() }
            .show()
    }

    private companion object {
        const val REQ_NOTIF = 42
    }
}
