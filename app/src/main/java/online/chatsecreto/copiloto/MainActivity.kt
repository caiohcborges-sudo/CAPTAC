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
import androidx.appcompat.app.AlertDialog
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
        val swAuto = findViewById<Switch>(R.id.swAuto)
        swAuto.isChecked = Prefs.auto(this)
        swAuto.setOnCheckedChangeListener { _, v ->
            Prefs.setAuto(this, v)
            Toast.makeText(this, if (v) "Modo AUTOMÁTICO ligado" else "Modo semi-automático", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btSalvar).setOnClickListener {
            Prefs.save(this, etUrl.text.toString(), etToken.text.toString(), etPkg.text.toString())
            Toast.makeText(this, "Salvo", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btAcc).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btNotif).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<Button>(R.id.btOverlay).setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        findViewById<Button>(R.id.btTest).setOnClickListener {
            Prefs.save(this, etUrl.text.toString(), etToken.text.toString(), etPkg.text.toString())
            Toast.makeText(this, "Testando…", Toast.LENGTH_SHORT).show()
            Thread {
                val r = Hub.ping(this)
                runOnUiThread { Toast.makeText(this, r, Toast.LENGTH_LONG).show() }
            }.start()
        }
        findViewById<TextView>(R.id.tvVer).text = "Instalada: build ${Updater.currentBuild(this)}"
        findViewById<Button>(R.id.btSim).setOnClickListener {
            Toast.makeText(this, "Simulando…", Toast.LENGTH_SHORT).show()
            Thread {
                val res = Hub.fetchFull(this, "Teste (app)", "oi, tudo bem?", false, Prefs.auto(this))
                runOnUiThread {
                    if (res == null) {
                        Hub.diag("SIMULAÇÃO — sem resposta da API (token, limite ou chave DeepSeek). Use TESTAR CONEXÃO API.")
                    } else {
                        val now = System.currentTimeMillis()
                        Hub.add(this, Hub.Item(now, "Teste (app)", "oi, tudo bem?", res.first, now, "teste"), null)
                        Hub.diag("SIMULAÇÃO ✅ a IA respondeu: ${res.first}")
                    }
                }
            }.start()
        }
        findViewById<Button>(R.id.btUpdate).setOnClickListener { checkUpdate(true) }
        findViewById<Button>(R.id.btUsePkg).setOnClickListener {
            val p = Hub.lastPkg
            if (p == null) {
                Toast.makeText(this, "Nenhuma notificação vista ainda", Toast.LENGTH_LONG).show()
            } else {
                etPkg.setText(p)
                Prefs.save(this, etUrl.text.toString(), etToken.text.toString(), p)
                Toast.makeText(this, "Pacote alvo = $p", Toast.LENGTH_LONG).show()
            }
        }
        findViewById<Button>(R.id.btBattery).setOnClickListener {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        applyDeepLink(intent)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private var askedUpdate = false

    /** Confere a release mais nova; se houver, pergunta se quer atualizar (manual = mostra também "já está atualizado"). */
    private fun checkUpdate(manual: Boolean) {
        Thread {
            val info = Updater.check(this)
            runOnUiThread {
                val cur = Updater.currentBuild(this)
                findViewById<TextView>(R.id.tvVer).text = if (info == null)
                    "Instalada: build $cur · é a mais nova ✅ (ou sem internet)"
                else "Instalada: build $cur · DISPONÍVEL: build ${info.build} ⬆"
                if (info == null) {
                    if (manual) Toast.makeText(this, "Você já está na versão mais nova.", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                AlertDialog.Builder(this)
                    .setTitle("Atualização disponível")
                    .setMessage("Build ${info.build} (você está na ${Updater.currentBuild(this)}). Atualizar agora?")
                    .setPositiveButton("ATUALIZAR") { _, _ ->
                        Toast.makeText(this, "Baixando…", Toast.LENGTH_LONG).show()
                        Thread {
                            Updater.downloadAndInstall(this, info) { m ->
                                runOnUiThread { Toast.makeText(this, m, Toast.LENGTH_LONG).show() }
                            }
                        }.start()
                    }
                    .setNegativeButton("Depois", null)
                    .show()
            }
        }.start()
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        applyDeepLink(i)
    }

    /** copiloto://config?url=...&token=... (QR do painel do WordPress). */
    private fun applyDeepLink(i: Intent?) {
        val d = i?.data ?: return
        if (d.scheme != "copiloto") return
        val u = d.getQueryParameter("url")
        val t = d.getQueryParameter("token")
        if (u.isNullOrBlank() || t.isNullOrBlank()) return
        Prefs.save(this, u, t, Prefs.pkg(this))
        etUrl.setText(u)
        etToken.setText(t)
        etPkg.setText(Prefs.pkg(this))
        Toast.makeText(this, "Configurado pelo QR ✅", Toast.LENGTH_LONG).show()
    }

    override fun onResume() {
        super.onResume()
        if (!askedUpdate) {
            askedUpdate = true
            checkUpdate(false) // ao abrir o app, já pergunta se houver versão nova
        }
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
        if (Hub.diagLog.isNotEmpty()) findViewById<TextView>(R.id.tvDiag).text = Hub.diagLog.joinToString("\n")
        val ago = if (Hub.lastAt == 0L) "nenhuma ainda" else "há ${(System.currentTimeMillis() - Hub.lastAt) / 1000}s"
        findViewById<TextView>(R.id.tvHealth).text =
            (if (n) "✅" else "❌") + " Acesso às notificações\n" +
            (if (Prefs.enabled(this)) "✅" else "❌") + " Receber sugestões ligado\n" +
            (if (Prefs.token(this).isNotBlank()) "✅" else "❌") + " Token preenchido\n" +
            (if (ReplyService.isEnabled(this)) "✅" else "➖") + " Envio pela tela (para quando não há resposta rápida)\n" +
            "API: ${Hub.lastApi}\n" +
            "Última notificação vista: $ago\n" +
            "Modo: " + (if (Prefs.auto(this)) "AUTOMÁTICO" else "semi-automático")
        val f = SimpleDateFormat("HH:mm", Locale.getDefault())
        tvHist.text = if (Hub.items.isEmpty()) "—" else Hub.items.take(15).joinToString("\n\n") {
            "${f.format(Date(it.time))} • ${it.sender}: ${it.message}\n→ ${it.reply}  [${it.status}]"
        }
    }
}
