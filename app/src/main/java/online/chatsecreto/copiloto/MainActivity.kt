package online.chatsecreto.copiloto

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
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
import org.json.JSONObject

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

        swAtivo.setOnCheckedChangeListener { _, v ->
            Prefs.setEnabled(this, v)
            refresh()
        }
        val etMax = findViewById<EditText>(R.id.etMaxDelay)
        etMax.setText(Prefs.maxDelay(this).toString())
        etMax.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                s?.toString()?.toIntOrNull()?.let { Prefs.setMaxDelay(this@MainActivity, it) }
            }
        })
        val swAds = findViewById<Switch>(R.id.swAds)
        swAds.isChecked = Prefs.closeAds(this)
        swAds.setOnCheckedChangeListener { _, v -> Prefs.setCloseAds(this, v) }
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
        findViewById<Button>(R.id.btScan).setOnClickListener {
            when {
                !ReplyService.isEnabled(this) ->
                    Toast.makeText(this, "Primeiro ative o ENVIO PELA TELA (botão 1) e ligue o Copiloto.", Toast.LENGTH_LONG).show()
                !Prefs.enabled(this) ->
                    Toast.makeText(this, "Ligue o switch 'Receber sugestões' primeiro.", Toast.LENGTH_LONG).show()
                else -> {
                    Scan.start(this, null)
                    Toast.makeText(this, "Abrindo o RandoChat e lendo as não lidas…", Toast.LENGTH_LONG).show()
                }
            }
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
        setupTabs()
        setupPlugin()
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }


    // ---------- abas ----------

    private lateinit var pages: List<View>
    private lateinit var tabs: List<TextView>
    private var cfgForm: CfgForm? = null
    private var lastStats = 0L

    private fun showTab(i: Int) {
        pages.forEachIndexed { k, p -> p.visibility = if (k == i) View.VISIBLE else View.GONE }
        tabs.forEachIndexed { k, t -> t.setTextColor(if (k == i) 0xFF00C875.toInt() else 0xFF8B949E.toInt()) }
        if (i == 1 && cfgForm?.loaded != true && Prefs.token(this).isNotBlank()) loadCfg()
    }

    private fun setupTabs() {
        pages = listOf(R.id.pageHome, R.id.pagePlugin, R.id.pageConn, R.id.pageLog).map { findViewById<View>(it) }
        tabs = listOf(R.id.tabHome, R.id.tabPlugin, R.id.tabConn, R.id.tabLog).map { findViewById<TextView>(it) }
        tabs.forEachIndexed { k, t -> t.setOnClickListener { showTab(k) } }
        showTab(if (Prefs.token(this).isBlank()) 2 else 0) // sem token ainda: começa pela Conexão
    }

    // ---------- configuração remota do plugin ----------

    private fun loadCfg() {
        val st = findViewById<TextView>(R.id.tvCfgStatus)
        st.text = "Carregando…"
        Thread {
            val r = Remote.get(this)
            runOnUiThread {
                val j = r.json
                if (j == null) {
                    st.text = "❌ ${r.error}"
                    return@runOnUiThread
                }
                val f = CfgForm(this, findViewById(R.id.llCfg))
                f.build(j)
                cfgForm = f
                showStats(j)
                st.text = "✅ Carregado — plugin v${j.optString("version")}. Edite e toque em SALVAR NO PLUGIN."
            }
        }.start()
    }

    private fun showStats(j: JSONObject) {
        val s = j.optJSONObject("stats") ?: return
        val fb = j.optJSONObject("fb")
        var t = "Atendidos hoje: ${s.optInt("today")} · Convites enviados: ${s.optInt("invites")} · Leads no total: ${s.optInt("total")}"
        if (fb != null) {
            val tot = fb.optInt("sent") + fb.optInt("edited") + fb.optInt("discarded")
            t += "\nSugestões: enviadas ${fb.optInt("sent")} · editadas ${fb.optInt("edited")} · descartadas ${fb.optInt("discarded")}"
            if (tot > 0) t += " · aproveitamento ${100 * (fb.optInt("sent") + fb.optInt("edited")) / tot}%"
        }
        findViewById<TextView>(R.id.tvStats).text = t
    }

    /** Atualiza só os números da aba Início (no máximo 1 vez por minuto). */
    private fun fetchStats() {
        if (Prefs.token(this).isBlank() || System.currentTimeMillis() - lastStats < 60000) return
        lastStats = System.currentTimeMillis()
        Thread {
            val r = Remote.get(this)
            val j = r.json ?: return@Thread
            runOnUiThread { showStats(j) }
        }.start()
    }

    private fun setupPlugin() {
        findViewById<Button>(R.id.btLoadCfg).setOnClickListener { loadCfg() }
        findViewById<Button>(R.id.btSaveCfg).setOnClickListener {
            val f = cfgForm
            val st = findViewById<TextView>(R.id.tvCfgStatus)
            if (f == null || !f.loaded) {
                Toast.makeText(this, "Toque em CARREGAR DO PLUGIN primeiro.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val vals = f.collect()
            st.text = "Salvando…"
            Thread {
                val r = Remote.save(this, vals)
                runOnUiThread {
                    val j = r.json
                    if (j == null) {
                        st.text = "❌ ${r.error}"
                    } else {
                        showStats(j)
                        st.text = "✅ Salvo no plugin."
                        Toast.makeText(this, "Salvo no plugin ✅", Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        }
        findViewById<Button>(R.id.btPurge).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Limpar registros")
                .setMessage("Apagar do plugin os leads com mais de 7 dias? Isso não pode ser desfeito.")
                .setPositiveButton("APAGAR") { _, _ ->
                    Thread {
                        val r = Remote.admin(this, "purge")
                        runOnUiThread {
                            val msg = r.json?.let { "Apagados: ${it.optInt("deleted")}" } ?: "❌ ${r.error}"
                            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                        }
                    }.start()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
        findViewById<Button>(R.id.btRegenToken).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Gerar novo token")
                .setMessage("O token antigo deixa de valer. O app salva o novo automaticamente, mas qualquer outro celular precisará do QR de novo.")
                .setPositiveButton("GERAR") { _, _ ->
                    Thread {
                        val r = Remote.admin(this, "regen_token")
                        runOnUiThread {
                            val t = r.json?.optString("token") ?: ""
                            if (t.isBlank()) {
                                Toast.makeText(this, "❌ ${r.error ?: "falhou"}", Toast.LENGTH_LONG).show()
                            } else {
                                Prefs.save(this, etUrl.text.toString(), t, etPkg.text.toString())
                                etToken.setText(t)
                                Toast.makeText(this, "Novo token salvo no app ✅", Toast.LENGTH_LONG).show()
                            }
                        }
                    }.start()
                }
                .setNegativeButton("Cancelar", null)
                .show()
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

    // Atualiza o painel de saúde a cada 3 s enquanto a tela está aberta.
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            tvHist.postDelayed(this, 3000)
        }
    }

    override fun onResume() {
        super.onResume()
        tvHist.removeCallbacks(tick)
        tvHist.postDelayed(tick, 3000)
        if (!askedUpdate) {
            askedUpdate = true
            checkUpdate(false) // ao abrir o app, já pergunta se houver versão nova
        }
        Hub.onChange = { runOnUiThread { refresh() } }
        refresh()
        fetchStats()
    }

    override fun onPause() {
        tvHist.removeCallbacks(tick)
        Hub.onChange = null
        super.onPause()
    }

    private fun refresh() {
        // O app pode ter pausado sozinho (aviso da plataforma): reflete nos interruptores.
        findViewById<Switch>(R.id.swAtivo).let { if (it.isChecked != Prefs.enabled(this)) it.isChecked = Prefs.enabled(this) }
        findViewById<Switch>(R.id.swAuto).let { if (it.isChecked != Prefs.auto(this)) it.isChecked = Prefs.auto(this) }
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
            (if (ReplyService.isEnabled(this)) "✅" else "❌") + " Envio pela tela (obrigatório: é o jeito padrão de responder)\n" +
            "API: ${Hub.lastApi}\n" +
            "Última notificação vista: $ago\n" +
            "Modo: " + (if (Prefs.auto(this)) "AUTOMÁTICO" else "semi-automático")
        val f = SimpleDateFormat("HH:mm", Locale.getDefault())
        tvHist.text = if (Hub.items.isEmpty()) "—" else Hub.items.take(15).joinToString("\n\n") {
            "${f.format(Date(it.time))} • ${it.sender}: ${it.message}\n→ ${it.reply}  [${it.status}]"
        }
    }
}
