package mg.acchadu.netspeed

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings

/**
 * Seul composant "visible" de l'app, et uniquement pour:
 *  1. obtenir SYSTEM_ALERT_WINDOW (impossible a accorder sans une Activity),
 *  2. afficher la modale de confirmation,
 *  3. se terminer.
 * Aucun ecran, aucune navigation.
 */
class BootstrapActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Settings.canDrawOverlays(this)) {
            requestOverlay()
            return
        }
        launchAndConfirm()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_OVERLAY) return
        if (Settings.canDrawOverlays(this)) {
            launchAndConfirm()
        } else {
            dialog("Permission refusee", "Sans l'autorisation \"Affichage par-dessus les autres applications\", le debit ne peut pas etre affiche.")
        }
    }

    private fun requestOverlay() {
        val i = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_OVERLAY)
    }

    private fun launchAndConfirm() {
        BandwidthService.start(this)
        dialog("NetSpeed", "Le trafic entrant et sortant est maintenant affiche en temps reel.")
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
        const val REQ_OVERLAY = 42
    }
}
