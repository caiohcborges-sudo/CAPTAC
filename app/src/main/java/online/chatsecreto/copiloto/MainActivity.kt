package online.chatsecreto.copiloto

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var etUrl: EditText
    private lateinit var etToken: EditText
    private lateinit var etPkg: EditText
    private lateinit var tvPerm: TextView
    private lateinit var tvHist: TextView
    private lateinit var swAtivo: Switch

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_main)
        Hub.load(this)

        etUrl = findViewById(R.id.etUrl)
        etToken = findViewById(R.id.etToken)
        etPkg = findViewById(R.id.etPkg)
        tvPerm = findViewById(R.id.tvPerm)
        tvHist = findViewById(R.id.tvHist)
        swAtivo = findViewById(R.id.swAtivo)

        etUrl.setText(Prefs.url(this))
        etToken.setText(Prefs.token(this))
        etPkg.setText(Prefs.pkg(this))
        swAtivo.isChecked = Prefs.enabled(this)

        swAtivo.setOnCheckedChangeListener { _, v -> Prefs.setEnabled(this, v) }
        findViewById<Button>(R.id.btSalvar).setOnClickListener {
            Prefs.save(this, etUrl.text.toString(), etToken.text.toString(), etPkg.text.toString())
            Toast.makeText(this, "Salvo", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btNotif).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<Button>(R.id.btOverlay).setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        Hub.onChange = { runOnUiThread { refresh() } }
        refresh()
    }

    override fun onPause() {
        Hub.onChange = null
        super.onPause()
    }

    private fun refresh() {
        val n = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        val o = Settings.canDrawOverlays(this)
        tvPerm.text = (if (n) "✅" else "❌") + " Acesso às notificações\n" +
            (if (o) "✅" else "❌") + " Sobrepor a outros apps (sem isso, uso a notificação)"
        val f = SimpleDateFormat("HH:mm", Locale.getDefault())
        tvHist.text = if (Hub.items.isEmpty()) "—" else Hub.items.take(15).joinToString("\n\n") {
            "${f.format(Date(it.time))} • ${it.sender}: ${it.message}\n→ ${it.reply}  [${it.status}]"
        }
    }
}
