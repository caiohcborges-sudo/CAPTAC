package online.chatsecreto.copiloto

import android.accessibilityservice.AccessibilityService
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.ConcurrentHashMap

/** Varredura das conversas NÃO LIDAS (só quem já escreveu para você). Não inicia conversas nem pula chats. */
object Scan {
    @Volatile var active = false
    @Volatile var busy = false
    @Volatile var until = 0L
    val seen: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun start(c: Context, ci: PendingIntent?) {
        seen.clear()
        busy = false
        active = true
        until = System.currentTimeMillis() + 5 * 60000L
        Hub.diag("varredura de não lidas iniciada")
        try {
            if (ci != null) ci.send() else Hub.launchTarget(c)
        } catch (_: Exception) {
            Hub.launchTarget(c)
        }
    }
}

/**
 * Lê a lista de conversas do app alvo, abre as não lidas, cola a resposta aprovada e envia.
 * Só age no app alvo, e só para responder mensagens recebidas.
 */
class ReplyService : AccessibilityService() {

    companion object {
        @Volatile var inst: ReplyService? = null

        fun isEnabled(c: Context): Boolean {
            val v = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return v.contains(c.packageName + "/" + ReplyService::class.java.name) || v.contains(c.packageName + "/." + ReplyService::class.java.simpleName)
        }

        fun inForeground(pkg: String): Boolean =
            inst?.rootInActiveWindow?.packageName?.toString().equals(pkg, ignoreCase = true)
    }

    private val h = Handler(Looper.getMainLooper())
    private var dumped = false
    private var listDumped = false
    private var lastBack = 0L

    private val tick = object : Runnable {
        override fun run() {
            try {
                process()
            } catch (_: Exception) {
            }
            h.postDelayed(this, 1200)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        inst = this
        h.postDelayed(tick, 1200)
    }

    override fun onDestroy() {
        h.removeCallbacks(tick)
        inst = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {}
    override fun onInterrupt() {}

    // ---------- núcleo ----------

    private fun isTarget(pkg: String?): Boolean {
        val p = (pkg ?: "").lowercase()
        return p.isNotEmpty() && Prefs.targets(this).any { p.contains(it) }
    }

    private fun process() {
        val now = System.currentTimeMillis()
        val s = Hub.screen
        if (s != null && now > s.until) {
            Hub.screen = null
            Hub.setStatus(this, s.id, "falhou (tela): tempo esgotado")
            Scan.busy = false
        }
        if (Scan.active && now > Scan.until) {
            Scan.active = false
            Scan.busy = false
            Hub.diag("varredura: tempo esgotado")
        }
        val cur = Hub.screen
        if (cur == null && !Scan.active) return
        val root = rootInActiveWindow ?: return
        if (!isTarget(root.packageName?.toString())) return
        val field = findEditable(root)
        if (field != null) inChat(root, field, cur) else onList(root, cur)
    }

    private fun back() {
        val now = System.currentTimeMillis()
        if (now - lastBack > 1500) {
            lastBack = now
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    private fun inChat(root: AccessibilityNodeInfo, field: AccessibilityNodeInfo, s: Hub.ScreenReply?) {
        if (s == null) { // varrendo e caiu num chat sem resposta pendente: volta
            back()
            return
        }
        if (field.text?.toString() != s.text) {
            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, s.text)
            if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return
        }
        val btn = findSend(root)
        if (btn != null && btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Hub.screen = null
            Hub.setStatus(this, s.id, "enviada (pela tela)")
            Hub.diag("✅ resposta enviada pela tela")
            dumped = false
            Scan.busy = false
            h.postDelayed({ back() }, 900)
        } else if (!dumped) {
            dumped = true
            Hub.diag("Texto colocado, mas não achei o botão de enviar. Botões na tela: " + clickables(root))
        }
    }

    // ---------- lista de conversas ----------

    class Row(val node: AccessibilityNodeInfo, val name: String, val msg: String, val unread: Boolean, val key: String)

    private val timeRe = Regex("^\\d{1,2}:\\d{2}$")
    private val badgeRe = Regex("^\\d{1,3}$")

    private fun collectRows(n: AccessibilityNodeInfo?, out: MutableList<Row>) {
        if (n == null) return
        if (n.isClickable) {
            val texts = mutableListOf<String>()
            gather(n, texts)
            if (texts.size >= 2) {
                val badge = texts.lastOrNull { badgeRe.matches(it) && texts.size >= 3 }
                val others = texts.filter { it != badge && !timeRe.matches(it) }
                if (others.isNotEmpty()) {
                    val name = others[0]
                    val msg = others.getOrNull(1) ?: ""
                    out.add(Row(n, name, msg, badge != null, "$name|$msg|${badge ?: ""}"))
                    return
                }
            }
        }
        for (i in 0 until n.childCount) collectRows(n.getChild(i), out)
    }

    private fun gather(n: AccessibilityNodeInfo?, out: MutableList<String>) {
        if (n == null) return
        val t = (n.text?.toString() ?: n.contentDescription?.toString() ?: "").trim()
        if (t.isNotEmpty() && n.childCount == 0) out.add(t)
        for (i in 0 until n.childCount) gather(n.getChild(i), out)
    }

    private fun isGeneric(name: String) = name.isBlank() || name.equals("Anônimo", true) || name.equals("Anonimo", true)

    private fun onList(root: AccessibilityNodeInfo, s: Hub.ScreenReply?) {
        val rows = mutableListOf<Row>()
        collectRows(root, rows)

        if (s != null) {
            // Há uma resposta pronta: abre a conversa certa.
            val it = Hub.items.firstOrNull { x -> x.id == s.id } ?: return
            if (!s.fromList) return
            val row = rows.firstOrNull { r -> r.name == it.sender && (r.msg == it.message || it.message.startsWith(r.msg.removeSuffix("…"))) }
                ?: rows.firstOrNull { r -> r.name == it.sender }
            if (row != null) row.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return
        }

        if (Scan.busy) return
        val row = rows.firstOrNull { it.unread && it.key !in Scan.seen }
        if (row == null) {
            Scan.active = false
            Hub.diag("varredura concluída: ${rows.size} conversas vistas, sem não lidas novas")
            if (rows.isEmpty() && !listDumped) {
                listDumped = true
                Hub.diag("Não reconheci linhas da lista. Itens clicáveis: " + clickables(root))
            }
            return
        }
        Scan.seen.add(row.key)
        Scan.busy = true
        val name = row.name
        val msg = row.msg
        val conv = if (isGeneric(name)) name + "#" + msg.take(20) else name
        val auto = Prefs.auto(this)
        val pkg = root.packageName?.toString() ?: ""
        Thread {
            val res = Hub.fetchFull(this, name, msg, false, auto, conv)
            if (res == null) {
                Hub.diag("lista: $name — sem sugestão (API pulou ou falhou: token, limite ou chave DeepSeek)")
                Scan.busy = false
                return@Thread
            }
            val now = System.currentTimeMillis()
            val item = Hub.Item(now, name, msg, res.first, now, "pendente", conv)
            Hub.add(this, item, null, null, pkg)
            Hub.diag("lista: $name — ✅ sugestão gerada · modo " + (if (auto) "automático" else "semi"))
            if (auto) {
                try {
                    Thread.sleep(res.second.coerceIn(0, 30) * 1000L)
                } catch (_: InterruptedException) {
                }
                if (!Hub.screenSend(this, item.id)) {
                    Scan.busy = false
                    CopilotoListener.showCard(item)
                }
            } else {
                CopilotoListener.showCard(item)
                Scan.busy = false // semi: segue gerando as próximas; você aprova pelo card
            }
        }.start()
    }

    // ---------- utilidades de nós ----------

    private fun findEditable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.isEditable || (n.className?.toString() ?: "").contains("EditText")) return n
        for (i in 0 until n.childCount) findEditable(n.getChild(i))?.let { return it }
        return null
    }

    private val sendRe = Regex("enviar|send|mandar|submit", RegexOption.IGNORE_CASE)

    private fun findSend(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        val label = (n.contentDescription?.toString() ?: "") + " " + (n.text?.toString() ?: "") + " " + (n.viewIdResourceName ?: "")
        if ((n.isClickable || n.isEnabled) && sendRe.containsMatchIn(label) && !n.isEditable) return n
        for (i in 0 until n.childCount) findSend(n.getChild(i))?.let { return it }
        return null
    }

    private fun clickables(n: AccessibilityNodeInfo?, out: MutableList<String> = mutableListOf()): String {
        if (n == null || out.size >= 10) return out.joinToString(" | ")
        if (n.isClickable) {
            out.add(
                (n.viewIdResourceName ?: "-") + "/" + (n.contentDescription ?: n.text ?: "-") + "/" +
                    (n.className?.toString() ?: "").substringAfterLast('.')
            )
        }
        for (i in 0 until n.childCount) clickables(n.getChild(i), out)
        return out.joinToString(" | ")
    }
}
