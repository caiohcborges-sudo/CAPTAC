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
    @Volatile var currentKey: String? = null
    @Volatile var lastProgress = 0L
    @Volatile var restarts = 0
    @Volatile var adsClosed = 0
    @Volatile var tRow = 0L
    @Volatile var tChat = 0L
    @Volatile var tApi = 0L
    @Volatile var lastEnd = 0L
    fun progress() {
        lastProgress = System.currentTimeMillis()
    }

    fun finish() {
        active = false
        lastEnd = System.currentTimeMillis()
    }
    val seen: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun start(c: Context, ci: PendingIntent?, launch: Boolean = true) {
        seen.clear()
        busy = false
        opening = null
        fetching = false
        active = true
        restarts = 0
        progress()
        until = System.currentTimeMillis() + 5 * 60000L
        Hub.diag("varredura de não lidas iniciada")
        if (!launch) return
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

    @Volatile private var winClass = ""

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            winClass = e.className?.toString() ?: ""
        }
        val adOn = Prefs.closeAds(this) && Prefs.enabled(this)
        if (Hub.screen == null && !Scan.active && !adOn) return
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
            Scan.finish()
            Scan.busy = false
            Hub.diag("varredura: tempo esgotado")
        }
        val cur = Hub.screen
        val working = cur != null || Scan.active
        val adOn = Prefs.closeAds(this) && Prefs.enabled(this)
        val autoOn = Prefs.auto(this) && Prefs.enabled(this)
        if (!working && !adOn && !autoOn) return
        // Vigia de tempo: atendendo e sem avanço há 45 s -> fecha e reabre o app.
        if (working && Scan.lastProgress > 0 && now - Scan.lastProgress > 45000) {
            recover()
            return
        }
        val root = rootInActiveWindow ?: return
        val pkgName = root.packageName?.toString() ?: ""
        // Anúncio por cima da conversa (do app alvo ou do Google): fecha só pelo botão de fechar.
        if (adOn && (isTarget(pkgName) || pkgName.startsWith("com.google.android")) && closeAd(root)) {
            Scan.progress()
            return
        }
        if (!working) {
            // Automático: começa sozinho assim que a lista do app mostra conversas não lidas.
            if (autoOn && isTarget(pkgName) && now - Scan.lastEnd > 20000 && findEditable(root) == null &&
                !((Hub.lastWhy == "off" || Hub.lastWhy == "hour_limit") && now - Hub.lastWhyAt < 60000)
            ) {
                root.getBoundsInScreen(listBounds)
                val rows = mutableListOf<Row>()
                collectRows(root, rows)
                if (rows.any { it.unread }) {
                    Hub.diag("lista com não lidas à vista → atendendo sozinho")
                    Scan.start(this, null, false)
                }
            }
            return
        }
        if (!isTarget(pkgName)) return
        val field = findEditable(root)
        if (field != null) inChat(root, field, cur) else onList(root, cur)
    }

    /** Sem avanço há 45 s: HOME e abre o app do zero (até 3 vezes por varredura). */
    private fun recover() {
        Scan.progress()
        Hub.screen?.let { Hub.setStatus(this, it.id, "falhou (tela): sem progresso") }
        Hub.screen = null
        Scan.opening = null
        Scan.fetching = false
        Scan.busy = false
        attemptsFor = -1
        if (Scan.restarts >= 3) {
            Scan.finish()
            Hub.diag("⏱ sem progresso e já reiniciei 3 vezes: parei. Confira o app.")
            return
        }
        Scan.restarts++
        Hub.diag("⏱ sem progresso há 45 s → fechando e reabrindo o RandoChat (${Scan.restarts}/3)")
        performGlobalAction(GLOBAL_ACTION_HOME)
        h.postDelayed({ Hub.relaunchFresh(this) }, 1200)
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
            Scan.tChat = System.currentTimeMillis()
            Scan.progress()
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

    private fun sec(ms: Long) = if (ms <= 0) "0" else String.format(java.util.Locale.US, "%.1f", ms / 1000.0)

    private fun sent(s: Hub.ScreenReply) {
        val t = System.currentTimeMillis()
        Scan.progress()
        Hub.screen = null
        Hub.setStatus(this, s.id, "enviada (pela tela)")
        Hub.diag(
            "✅ resposta enviada pela tela · abrir ${sec(Scan.tChat - Scan.tRow)}s · conversa+IA ${sec(Scan.tApi - Scan.tChat)}s · " +
                "espera+enviar ${sec(t - Scan.tApi)}s · total ${sec(t - Scan.tRow)}s"
        )
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

    /**
     * Botão de enviar = o elemento da faixa mais à direita (círculo laranja), nunca o clipe de anexo.
     * Tentativa 1: clicar no botão achado (ou tocar na posição); 2: tocar no centro; 3: tocar um pouco mais à direita; 4: Enter.
     */
    private fun sendOnce(root: AccessibilityNodeInfo, field: AccessibilityNodeInfo, n: Int) {
        val btn = findSend(root, field)
        val rb = android.graphics.Rect()
        root.getBoundsInScreen(rb)
        val fb = android.graphics.Rect()
        field.getBoundsInScreen(fb)
        val y = fb.exactCenterY()
        val estX = rb.left + 0.917f * rb.width() // centro do círculo de enviar (à direita do campo)
        val r = android.graphics.Rect()
        if (btn != null) btn.getBoundsInScreen(r)
        when (n) {
            1 -> {
                if (btn != null) {
                    Hub.diag("tentativa 1: clique no botão de enviar achado")
                    btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } else {
                    Hub.diag("tentativa 1: toque na posição do botão de enviar (${estX.toInt()}, ${y.toInt()})")
                    tap(estX, y)
                }
            }
            2 -> {
                val x = if (btn != null) r.exactCenterX() else estX
                val yy = if (btn != null) r.exactCenterY() else y
                Hub.diag("tentativa 2: toque em (${x.toInt()}, ${yy.toInt()})")
                tap(x, yy)
            }
            3 -> {
                val x = rb.left + 0.90f * rb.width()
                Hub.diag("tentativa 3: toque em (${x.toInt()}, ${y.toInt()})")
                tap(x, y)
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
            Scan.tApi = now
            Scan.progress()
            val item = Hub.Item(now, op.name, message, res.first, now, "pendente", op.conv)
            Hub.add(this, item, null, null, pkg)
            Hub.diag("${op.name} — ✅ sugestão gerada · modo " + (if (auto) "automático" else "semi"))
            if (auto) {
                try {
                    Thread.sleep(minOf(res.second.coerceIn(0, 30), Prefs.maxDelay(this)) * 1000L)
                } catch (_: InterruptedException) {
                }
                Scan.progress()
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

    private val listBounds = android.graphics.Rect()

    /** Banner/anúncio da lista (ex.: "Timo… ABRIR"): nunca é conversa e nunca recebe toque. */
    private fun looksLikeAd(n: AccessibilityNodeInfo, texts: List<String>): Boolean {
        val cls = n.className?.toString() ?: ""
        val vid = n.viewIdResourceName ?: ""
        if (cls.contains("AdView") || cls.contains("NativeAd") || cls.contains("gms.ads") ||
            vid.contains("banner", true) || vid.contains("ad_", true) || vid.contains("_ad", true)
        ) return true
        if (texts.any {
                val t = it.trim()
                adActionRe.matches(t) || adBadgeRe.containsMatchIn(t) ||
                    t.equals("Grátis", true) || t.equals("Gratuito", true) || t.equals("Free", true)
            }
        ) return true
        val r = android.graphics.Rect()
        n.getBoundsInScreen(r)
        // faixa de baixo da tela (onde fica o banner), mesmo que não tenha rótulo
        return listBounds.height() > 0 && r.centerY() > listBounds.top + 0.87f * listBounds.height()
    }

    private fun collectRows(n: AccessibilityNodeInfo?, out: MutableList<Row>) {
        if (n == null) return
        if (n.isClickable) {
            val texts = mutableListOf<String>()
            gather(n, texts)
            if (looksLikeAd(n, texts)) return // não toca nem desce no anúncio
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

    private var unknownSince = 0L

    private fun onList(root: AccessibilityNodeInfo, s: Hub.ScreenReply?) {
        root.getBoundsInScreen(listBounds)
        val rows = mutableListOf<Row>()
        collectRows(root, rows)

        // Nem lista (sem conversas à vista) nem chat: provavelmente um anúncio ou tela estranha.
        if (rows.isEmpty()) {
            val nowU = System.currentTimeMillis()
            if (unknownSince == 0L) unknownSince = nowU
            val elU = nowU - unknownSince
            if (elU > 6000 && nowU - lastRelaunch > 8000) {
                lastRelaunch = nowU
                Scan.restarts++
                if (Scan.restarts > 6) {
                    Scan.finish()
                    Hub.diag("tela desconhecida e já saí/entrei 6 vezes: parei. Confira o app.")
                    return
                }
                val tx = mutableListOf<String>()
                gather(root, tx)
                Hub.diag(
                    "tela desconhecida há ${elU / 1000}s (${winClass.substringAfterLast('.')}) textos: " +
                        tx.take(6).joinToString(" | ") + " → saindo e entrando no app"
                )
                exitReenter(elU > 20000)
            }
            return
        }
        unknownSince = 0L

        if (s != null) {
            // Há uma resposta pronta: abre a conversa certa.
            val it = Hub.items.firstOrNull { x -> x.id == s.id } ?: return
            val row = rows.firstOrNull { r -> r.name == it.sender && (r.msg == it.message || it.message.startsWith(r.msg.removeSuffix("…"))) }
                ?: rows.firstOrNull { r -> r.name == it.sender }
            if (row != null) row.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return
        }

        val op = Scan.opening
        if (op != null && System.currentTimeMillis() - op.at > 5000 && !Scan.fetching) {
            Hub.diag("a conversa de ${op.name} não abriu (anúncio no meio?) → tentando de novo")
            Scan.currentKey?.let { Scan.seen.remove(it) }
            Scan.opening = null
            Scan.busy = false
        }
        if (Scan.busy) return
        val row = rows.firstOrNull { it.unread && it.key !in Scan.seen }
        if (row == null) {
            Scan.finish()
            Hub.diag("varredura concluída: ${rows.size} conversas vistas, sem não lidas novas")
            if (rows.isEmpty() && !listDumped) {
                listDumped = true
                Hub.diag("Não reconheci linhas da lista. Itens clicáveis: " + clickables(root))
            }
            return
        }
        Scan.seen.add(row.key)
        Scan.currentKey = row.key
        Scan.busy = true
        Scan.fetching = false
        val conv = if (isGeneric(row.name)) row.name + "#" + row.msg.take(20) else row.name
        Scan.opening = Scan.Open(row.name, row.msg, conv, System.currentTimeMillis())
        Hub.diag("abrindo a conversa de ${row.name}")
        Scan.tRow = System.currentTimeMillis()
        Scan.progress()
        row.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }


    // ---------- anúncios ----------

    private val closeRe = Regex("^\\s*(fechar|close|dismiss|dispensar|pular|skip|ignorar|×|✕|✖|⨯|x)\\s*(an[uú]ncio|ad)?\\s*$", RegexOption.IGNORE_CASE)
    private val closeIdRe = Regex("(^|[:/_])(close|dismiss|skip|cross|btn_x)([_a-z0-9]*)$", RegexOption.IGNORE_CASE)

    // Tela de anúncio pelo NOME da janela (funciona mesmo quando o conteúdo não é legível): AdMob e outras redes.
    private val adWindowRe = Regex("(\\.ads?\\.|AdActivity|AdsActivity|AdMob|AdView|Interstitial|Rewarded|applovin|unity3d|vungle|chartboost|ironsource|inmobi|mbridge|mintegral|pangle|adcolony|audiencenetwork|fyber|startapp|tapjoy)", RegexOption.IGNORE_CASE)

    // Anúncio pelo conteúdo = selo (Google Play / Anúncio / Patrocinado / Ad) E botão de ação (Abrir / Instalar...), com o texto inteiro do elemento.
    private val adBadgeRe = Regex("^(google play|an[uú]ncio|patrocinad|sponsored|ad\\b)", RegexOption.IGNORE_CASE)
    private val adActionRe = Regex("^(abrir|instalar|install|open|baixar|download|jogar|visitar|visitar site|saiba mais|learn more)$", RegexOption.IGNORE_CASE)
    private val badBtnRe = Regex("abrir|instalar|install|open|saiba|learn|baixar|download|jogar|visitar|play", RegexOption.IGNORE_CASE)
    private var adSeenAt = 0L
    private var adBackAt = 0L
    private var adBacks = 0
    private var lastAdClick = 0L
    private var lastRelaunch = 0L

    /** A tela é do próprio RandoChat (lista ou chat)? Então "Abrir" é só o banner de rodapé, não um anúncio em tela cheia. */
    private fun isAppScreen(leaves: List<String>) =
        leaves.any { it.equals("Não lidas", true) || it.equals("Todas", true) || it.startsWith("Digite aqui", true) }

    /**
     * true = anúncio em tela cheia na tela (tratado). Estratégias, em ordem, repetidas a cada 0,5 s
     * (o botão costuma aparecer só no fim da contagem):
     * 1) botão com texto Fechar/Close/×;  2) botão pelo identificador interno (close/dismiss/skip);
     * 3) ícone × sem texto no canto de cima;  4) tecla Voltar aos 4 s e aos 8 s;
     * 5) aos 12 s: abre o app do zero (derruba o anúncio).
     * Nunca toca em Abrir/Instalar nem no corpo do anúncio.
     */
    private fun closeAd(root: AccessibilityNodeInfo): Boolean {
        val byClass = adWindowRe.containsMatchIn(winClass)
        var isAd = byClass
        if (!isAd) {
            val leaves = mutableListOf<String>()
            gather(root, leaves)
            isAd = leaves.any { adBadgeRe.containsMatchIn(it.trim()) } && leaves.any { adActionRe.matches(it.trim()) } &&
                !isAppScreen(leaves) && findEditable(root) == null
        }
        if (!isAd) {
            adSeenAt = 0L
            adBacks = 0
            return false
        }
        val now = System.currentTimeMillis()
        if (adSeenAt == 0L) {
            adSeenAt = now
            adBacks = 0
            Hub.diag("anúncio detectado (${if (byClass) "pela tela: " + winClass.substringAfterLast('.') else "pelo conteúdo"}) — esperando o botão de fechar")
        }
        var target = findClose(root)
        var how = "texto/identificador"
        if (target == null) {
            target = findCornerClose(root)
            how = "ícone no canto"
        }
        if (target != null) {
            if (now - lastAdClick > 1200) {
                lastAdClick = now
                Scan.adsClosed++
                Hub.diag("→ fechando o anúncio ($how) · fechados: ${Scan.adsClosed}")
                clickUp(target)
            }
            return true
        }
        val el = now - adSeenAt
        if (el > 3000 && now - lastRelaunch > 8000) {
            lastRelaunch = now
            val hard = el > 15000
            Hub.diag("anúncio há ${el / 1000}s sem botão de fechar → saindo e entrando de novo no app" + (if (hard) " (reinício completo)" else ""))
            exitReenter(hard)
        }
        return true
    }

    /** HOME e volta ao app (como você faz com a mão). hard = abre do zero, limpando as telas. */
    private fun exitReenter(hard: Boolean) {
        Scan.progress()
        performGlobalAction(GLOBAL_ACTION_HOME)
        h.postDelayed({ if (hard) Hub.relaunchFresh(this) else Hub.launchTarget(this) }, 700)
    }

    /** Clica no nó ou no primeiro ancestral clicável; se nada responder, toca no centro do próprio botão de fechar. */
    private fun clickUp(node: AccessibilityNodeInfo) {
        var n: AccessibilityNodeInfo? = node
        var done = false
        while (n != null && !done) {
            if (n.isClickable) done = n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            n = n.parent
        }
        if (!done) {
            val r = android.graphics.Rect()
            node.getBoundsInScreen(r)
            tap(r.exactCenterX(), r.exactCenterY())
        }
    }

    private fun findClose(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        val label = (n.text?.toString() ?: n.contentDescription?.toString() ?: "")
        if (label.isNotBlank() && closeRe.matches(label)) return n
        val vid = n.viewIdResourceName ?: ""
        if (vid.isNotEmpty() && closeIdRe.containsMatchIn(vid) && !badBtnRe.containsMatchIn(label)) return n
        for (i in 0 until n.childCount) findClose(n.getChild(i))?.let { return it }
        return null
    }

    /** Ícone de fechar sem texto: pequeno, no topo e num canto (direita de preferência). Nunca algo rotulado Abrir/Instalar. */
    private fun findCornerClose(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val rb = android.graphics.Rect()
        root.getBoundsInScreen(rb)
        val d = resources.displayMetrics.density
        var best: AccessibilityNodeInfo? = null
        var bestScore = -1f
        fun walk(x: AccessibilityNodeInfo?) {
            if (x == null) return
            val r = android.graphics.Rect()
            x.getBoundsInScreen(r)
            val cls = x.className?.toString() ?: ""
            val label = (x.text?.toString() ?: "") + " " + (x.contentDescription?.toString() ?: "")
            val small = r.width() in (20 * d).toInt()..(84 * d).toInt() && r.height() in (20 * d).toInt()..(84 * d).toInt()
            val top = r.top < rb.top + rb.height() * 0.20f
            val clickish = x.isClickable || cls.contains("Image") || cls.contains("Button")
            if (small && top && clickish && !badBtnRe.containsMatchIn(label)) {
                val cx = r.exactCenterX()
                val score = when {
                    cx > rb.left + rb.width() * 0.75f -> cx - rb.left // canto direito: o mais à direita
                    cx < rb.left + rb.width() * 0.25f -> (rb.width() * 0.5f) - (cx - rb.left) // canto esquerdo
                    else -> -1f
                }
                if (score > bestScore) {
                    bestScore = score
                    best = x
                }
            }
            for (i in 0 until x.childCount) walk(x.getChild(i))
        }
        walk(root)
        return best
    }

    // ---------- utilidades de nós ----------

    private fun findEditable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.isEditable || (n.className?.toString() ?: "").contains("EditText")) return n
        for (i in 0 until n.childCount) findEditable(n.getChild(i))?.let { return it }
        return null
    }

    private val sendRe = Regex("enviar|send|mandar|submit", RegexOption.IGNORE_CASE)
    private val notSendRe = Regex("anex|attach|clip|emoji|sticker|figurinha|gif|c[aâ]mera|galeria|image|foto", RegexOption.IGNORE_CASE)

    /** Rótulo "enviar/send"; senão o clicável mais à direita (acima de 80% da largura) na linha do campo, que não seja anexo/emoji. */
    private fun findSend(n: AccessibilityNodeInfo?, field: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        findSendByLabel(n)?.let { return it }
        val rb = android.graphics.Rect()
        n?.getBoundsInScreen(rb)
        val fb = android.graphics.Rect()
        field.getBoundsInScreen(fb)
        var best: AccessibilityNodeInfo? = null
        var bestX = -1f
        fun walk(x: AccessibilityNodeInfo?) {
            if (x == null) return
            if (x.isClickable && !x.isEditable) {
                val r = android.graphics.Rect()
                x.getBoundsInScreen(r)
                val label = (x.contentDescription?.toString() ?: "") + " " + (x.text?.toString() ?: "") + " " + (x.viewIdResourceName ?: "")
                val sameRow = r.centerY() in (fb.top - fb.height())..(fb.bottom + fb.height())
                val farRight = r.exactCenterX() > rb.left + rb.width() * 0.80f
                if (sameRow && farRight && !notSendRe.containsMatchIn(label) && r.exactCenterX() > bestX) {
                    best = x
                    bestX = r.exactCenterX()
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
        if ((n.isClickable || n.isEnabled) && sendRe.containsMatchIn(label) && !notSendRe.containsMatchIn(label) && !n.isEditable) return n
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
