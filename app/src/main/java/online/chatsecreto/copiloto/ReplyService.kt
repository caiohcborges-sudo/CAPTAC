package online.chatsecreto.copiloto

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
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
    class Open(val name: String, val msg: String, val conv: String, val at: Long)
    @Volatile var opening: Open? = null
    @Volatile var fetching = false
    val seen: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun start(c: Context, ci: PendingIntent?) {
        seen.clear()
        busy = false
        opening = null
        fetching = false
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
    private var attemptsFor = -1L
    private var tries = 0
    private var lastClickAt = 0L
    private var listDumped = false
    private var lastBack = 0L

    private val tick = object : Runnable {
        override fun run() {
            try {
                process()
            } catch (_: Exception) {
            }
            h.postDelayed(this, 500)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        inst = this
        h.postDelayed(tick, 500)
    }

    override fun onDestroy() {
        h.removeCallbacks(tick)
        inst = null
        super.onDestroy()
    }

    private var lastEvent = 0L

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (Hub.screen == null && !Scan.active) return
        val now = System.currentTimeMillis()
        if (now - lastEvent < 250) return
        lastEvent = now
        h.post {
            try {
                process()
            } catch (_: Exception) {
            }
        }
    }
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
            Scan.opening = null
            Scan.fetching = false
        }
        if (Scan.active && now > Scan.until) {
            Scan.active = false
            Scan.busy = false
            Hub.diag("varredura: tempo esgotado")
        }
        val cur = Hub.screen
        if (cur == null && !Scan.active) return
        val root = rootInActiveWindow ?: return
        val pkgName = root.packageName?.toString() ?: ""
        // Anúncio por cima da conversa (do app alvo ou do Google): fecha só pelo botão "Fechar".
        if ((isTarget(pkgName) || pkgName.startsWith("com.google.android")) && Prefs.closeAds(this) && closeAd(root)) return
        if (!isTarget(pkgName)) return
        val field = findEditable(root)
        if (field != null) inChat(root, field, cur) else onList(root, cur)
    }

    fun requestBack() {
        h.post { performGlobalAction(GLOBAL_ACTION_BACK) }
    }

    private fun back() {
        val now = System.currentTimeMillis()
        if (now - lastBack > 900) {
            lastBack = now
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    private fun inChat(root: AccessibilityNodeInfo, field: AccessibilityNodeInfo, s: Hub.ScreenReply?) {
        if (s == null) {
            val op = Scan.opening
            if (op == null) { // chat sem varredura em curso: volta
                back()
                return
            }
            if (Scan.fetching) return
            Scan.fetching = true
            val incoming = readIncoming(root, field)
            val message = if (incoming.isEmpty()) op.msg else incoming.takeLast(3).joinToString(" | ")
            Hub.diag("lendo a conversa de ${op.name}: \"$message\"")
            startFetch(op, message, root.packageName?.toString() ?: "")
            return
        }
        val now = System.currentTimeMillis()
        if (attemptsFor != s.id) {
            attemptsFor = s.id
            tries = 0
            lastClickAt = 0L
        }
        val cur = field.text?.toString() ?: ""
        // 1) cola o texto
        if (lastClickAt == 0L && cur != s.text) {
            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, s.text)
            field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            return
        }
        // 2) depois de cada tentativa, confere se o campo esvaziou (= enviou)
        if (lastClickAt > 0L && now - lastClickAt > 1000) {
            if (cur != s.text) {
                sent(s)
                return
            }
            if (tries >= 4) {
                Hub.screen = null
                Hub.setStatus(this, s.id, "falhou (tela): não consegui tocar em enviar")
                Hub.diag("❌ Não consegui enviar depois de 4 tentativas. Botões na tela: " + clickables(root))
                Scan.opening = null
                Scan.fetching = false
                Scan.busy = false
                attemptsFor = -1
                return
            }
        }
        // 3) tenta enviar (uma estratégia por tentativa)
        if (lastClickAt == 0L || now - lastClickAt > 1000) {
            tries++
            lastClickAt = now
            sendOnce(root, field, tries)
        }
    }

    private fun sent(s: Hub.ScreenReply) {
        Hub.screen = null
        Hub.setStatus(this, s.id, "enviada (pela tela)")
        Hub.diag("✅ resposta enviada pela tela")
        attemptsFor = -1
        Scan.opening = null
        Scan.fetching = false
        Scan.busy = false
        h.postDelayed({ back() }, 500)
    }

    private fun tap(x: Float, y: Float) {
        val path = Path()
        path.moveTo(x, y)
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 70)).build()
        dispatchGesture(g, null, null)
    }

    /** Tentativa 1: clicar no botão achado; 2: tocar no centro dele; 3: tocar à direita do campo; 4: tecla Enter. */
    private fun sendOnce(root: AccessibilityNodeInfo, field: AccessibilityNodeInfo, n: Int) {
        val btn = findSend(root, field)
        val rb = android.graphics.Rect()
        root.getBoundsInScreen(rb)
        val fb = android.graphics.Rect()
        field.getBoundsInScreen(fb)
        when (n) {
            1 -> {
                Hub.diag("tentativa 1: clique no botão " + (if (btn != null) "achado" else "(não achei botão)"))
                btn?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            2 -> {
                val r = android.graphics.Rect()
                if (btn != null) btn.getBoundsInScreen(r)
                val x = if (btn != null) r.exactCenterX() else (fb.right + rb.right) / 2f
                val y = if (btn != null) r.exactCenterY() else fb.exactCenterY()
                Hub.diag("tentativa 2: toque na tela em (${x.toInt()}, ${y.toInt()})")
                tap(x, y)
            }
            3 -> {
                val x = (fb.right + rb.right) / 2f
                Hub.diag("tentativa 3: toque à direita do campo em (${x.toInt()}, ${fb.centerY()})")
                tap(x, fb.exactCenterY())
            }
            else -> {
                Hub.diag("tentativa 4: tecla Enter")
                if (Build.VERSION.SDK_INT >= 30) {
                    field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                }
            }
        }
    }

    /** Mensagens recebidas (balões à esquerda) visíveis no chat. */
    private fun readIncoming(root: AccessibilityNodeInfo, field: AccessibilityNodeInfo): List<String> {
        val rb = android.graphics.Rect()
        root.getBoundsInScreen(rb)
        val fb = android.graphics.Rect()
        field.getBoundsInScreen(fb)
        val leaves = mutableListOf<Pair<android.graphics.Rect, String>>()
        collectLeaves(root, leaves)
        val skip = Regex("^(hoje|ontem|online|offline|digitando.*)$", RegexOption.IGNORE_CASE)
        val tail = Regex("\\s*\\d{1,2}:\\d{2}$")
        return leaves
            .filter { (r, t) ->
                r.top > rb.top + rb.height() * 0.14 && r.bottom < fb.top &&
                    r.left < rb.left + rb.width() * 0.12 &&
                    !timeRe.matches(t) && !skip.matches(t)
            }
            .sortedBy { it.first.top }
            .map { it.second.replace(tail, "").trim() }
            .filter { it.isNotEmpty() }
    }

    private fun collectLeaves(n: AccessibilityNodeInfo?, out: MutableList<Pair<android.graphics.Rect, String>>) {
        if (n == null) return
        val t = (n.text?.toString() ?: "").trim()
        if (t.isNotEmpty() && n.childCount == 0 && !n.isEditable) {
            val r = android.graphics.Rect()
            n.getBoundsInScreen(r)
            out.add(Pair(r, t))
        }
        for (i in 0 until n.childCount) collectLeaves(n.getChild(i), out)
    }

    private fun startFetch(op: Scan.Open, message: String, pkg: String) {
        val auto = Prefs.auto(this)
        Thread {
            val res = Hub.fetchFull(this, op.name, message, false, auto, op.conv)
            if (res == null) {
                Hub.diag("${op.name} — sem resposta: ${when (Hub.lastWhy) { "off" -> "captação DESLIGADA no painel do plugin"; "hour_limit" -> "limite por hora atingido"; "user_limit" -> "limite de respostas dessa pessoa"; "sequence_end" -> "sequência de mensagens terminou"; "system" -> "mensagem de sistema"; "" -> "token, chave ou conexão"; else -> Hub.lastWhy }}")
                Scan.opening = null
                Scan.fetching = false
                Scan.busy = false
                requestBack()
                return@Thread
            }
            val now = System.currentTimeMillis()
            val item = Hub.Item(now, op.name, message, res.first, now, "pendente", op.conv)
            Hub.add(this, item, null, null, pkg)
            Hub.diag("${op.name} — ✅ sugestão gerada · modo " + (if (auto) "automático" else "semi"))
            if (auto) {
                try {
                    Thread.sleep(res.second.coerceIn(0, 30) * 1000L)
                } catch (_: InterruptedException) {
                }
                Hub.screen = Hub.ScreenReply(item.id, item.reply, pkg, System.currentTimeMillis() + 60000, fromList = false)
                Hub.setStatus(this, item.id, "enviando pela tela…")
            } else {
                CopilotoListener.showCard(item) // chat fica aberto até você aprovar ou descartar
            }
        }.start()
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

        val op = Scan.opening
        if (op != null && System.currentTimeMillis() - op.at > 15000 && !Scan.fetching) {
            Scan.opening = null
            Scan.busy = false
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
        Scan.fetching = false
        val conv = if (isGeneric(row.name)) row.name + "#" + row.msg.take(20) else row.name
        Scan.opening = Scan.Open(row.name, row.msg, conv, System.currentTimeMillis())
        Hub.diag("abrindo a conversa de ${row.name}")
        row.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }


    // ---------- anúncios ----------

    private val closeRe = Regex("^\\s*(fechar|close|dismiss|pular|skip|×|✕|x)\\s*(an[uú]ncio|ad)?\\s*$", RegexOption.IGNORE_CASE)
    private val adMarkRe = Regex("google play|an[uú]ncio|patrocinad|sponsored|instalar|install|\\bad\\b|abrir", RegexOption.IGNORE_CASE)
    private var adSeenAt = 0L
    private var adBackAt = 0L

    /** true = há um anúncio na tela (tratado). Só toca em "Fechar"; nunca em Abrir/Instalar. */
    private fun closeAd(root: AccessibilityNodeInfo): Boolean {
        val btn = findClose(root) ?: return false
        val leaves = mutableListOf<String>()
        gather(root, leaves)
        if (leaves.none { adMarkRe.containsMatchIn(it) }) return false // "Fechar" sem cara de anúncio: ignora
        Hub.diag("anúncio detectado → tocando em Fechar")
        var n: AccessibilityNodeInfo? = btn
        var done = false
        while (n != null && !done) {
            if (n.isClickable) done = n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            n = n.parent
        }
        if (!done) {
            val r = android.graphics.Rect()
            btn.getBoundsInScreen(r)
            tap(r.exactCenterX(), r.exactCenterY())
        }
        return true
    }

    private fun findClose(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        val label = (n.text?.toString() ?: n.contentDescription?.toString() ?: "")
        if (label.isNotBlank() && closeRe.matches(label)) return n
        for (i in 0 until n.childCount) findClose(n.getChild(i))?.let { return it }
        return null
    }

    // ---------- utilidades de nós ----------

    private fun findEditable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.isEditable || (n.className?.toString() ?: "").contains("EditText")) return n
        for (i in 0 until n.childCount) findEditable(n.getChild(i))?.let { return it }
        return null
    }

    private val sendRe = Regex("enviar|send|mandar|submit", RegexOption.IGNORE_CASE)

    private fun findSend(n: AccessibilityNodeInfo?, field: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        findSendByLabel(n)?.let { return it }
        // Reserva: o botão clicável à direita do campo de texto, na mesma altura.
        val fb = android.graphics.Rect()
        field.getBoundsInScreen(fb)
        var best: AccessibilityNodeInfo? = null
        var bestLeft = -1
        fun walk(x: AccessibilityNodeInfo?) {
            if (x == null) return
            if (x.isClickable && !x.isEditable) {
                val r = android.graphics.Rect()
                x.getBoundsInScreen(r)
                val sameRow = r.centerY() in (fb.top - 60)..(fb.bottom + 60)
                if (sameRow && r.left >= fb.right - 24 && r.left > bestLeft) {
                    best = x
                    bestLeft = r.left
                }
            }
            for (i in 0 until x.childCount) walk(x.getChild(i))
        }
        walk(n)
        return best
    }

    private fun findSendByLabel(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        val label = (n.contentDescription?.toString() ?: "") + " " + (n.text?.toString() ?: "") + " " + (n.viewIdResourceName ?: "")
        if ((n.isClickable || n.isEnabled) && sendRe.containsMatchIn(label) && !n.isEditable) return n
        for (i in 0 until n.childCount) findSendByLabel(n.getChild(i))?.let { return it }
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
