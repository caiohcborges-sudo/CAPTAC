package online.chatsecreto.copiloto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import java.util.concurrent.ConcurrentHashMap

class CopilotoListener : NotificationListenerService() {

    private val main = Handler(Looper.getMainLooper())
    private val lastSeen = ConcurrentHashMap<String, Long>()
    private var overlay: View? = null

    companion object {
        fun notifId(id: Long) = (id % 100000).toInt() + 1000
        private val SYSTEM = Regex("procurando|conectado|desconectado|saiu do chat|an[uú]ncio|digitando", RegexOption.IGNORE_CASE)
    }

    private val updateTick = object : Runnable {
        override fun run() {
            Thread {
                Updater.check(this@CopilotoListener)?.let { Updater.notifyAvailable(this@CopilotoListener, it) }
            }.start()
            main.postDelayed(this, 6 * 60 * 60 * 1000L) // confere a cada 6 h
        }
    }

    override fun onCreate() {
        super.onCreate()
        Hub.load(this)
        main.postDelayed(updateTick, 15000)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification
        val pkg = sbn.packageName.lowercase()
        if (pkg == packageName) return
        Hub.lastPkg = sbn.packageName
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        // Texto: normal, expandido ou a última mensagem de um estilo de conversa.
        var text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (text.isEmpty()) text = ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            val msgs = ex.getParcelableArray(Notification.EXTRA_MESSAGES)
            val last = msgs?.lastOrNull() as? android.os.Bundle
            text = last?.getCharSequence("text")?.toString()?.trim().orEmpty()
        }
        val tag = "${sbn.packageName} | $title"
        if (!Prefs.enabled(this)) { Hub.diag("$tag — ignorada: switch 'Receber sugestões' desligado"); return }
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) { Hub.diag("$tag — ignorada: resumo de grupo"); return }
        if (Prefs.targets(this).none { pkg.contains(it) }) { Hub.diag("$tag — ignorada: pacote não bate com o alvo"); return }
        if (title.isEmpty() || text.isEmpty()) { Hub.diag("$tag — ignorada: sem título ou texto"); return }
        if (text.length <= 60 && SYSTEM.containsMatchIn(text)) { Hub.diag("$tag — ignorada: mensagem de sistema"); return }

        // Duplicada em menos de 3s.
        val k = "$title|$text"
        val now = System.currentTimeMillis()
        if (now - (lastSeen[k] ?: 0L) < 3000) return
        lastSeen[k] = now
        if (lastSeen.size > 200) lastSeen.entries.removeIf { now - it.value > 60000 }

        // Ação de resposta da notificação original (só será usada após o toque).
        var pend: Hub.Pending? = null
        n.actions?.forEach { a ->
            val ri = a.remoteInputs?.firstOrNull()
            if (pend == null && ri != null && a.actionIntent != null) pend = Hub.Pending(a.actionIntent, ri)
        }

        val auto = Prefs.auto(this)
        val hasReplyAction = pend != null
        Thread {
            val res = Hub.fetchFull(this, title, text, false, auto)
            if (res == null) {
                Hub.diag("$tag — sem sugestão: API pulou ou falhou (token, limite ou chave DeepSeek)")
                return@Thread
            }
            Hub.diag("$tag — ✅ sugestão gerada")
            val item = Hub.Item(now, title, text, res.first, now, "pendente")
            Hub.add(this, item, pend)
            if (auto && hasReplyAction) {
                // Modo automático: espera o delay (tempo de "digitação") e envia sozinho.
                try {
                    Thread.sleep(res.second.coerceIn(0, 30) * 1000L)
                } catch (_: InterruptedException) {
                }
                if (Hub.autoSend(this, item.id)) return@Thread
            }
            main.post { present(item) }
        }.start()
    }

    private fun buzz() {
        try {
            val vb = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            vb.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {
        }
    }

    private var queueTv: TextView? = null

    private fun refreshQueue() {
        val n = Hub.pendingCount() - 1
        queueTv?.let {
            it.visibility = if (n > 0) View.VISIBLE else View.GONE
            it.text = "+$n na fila"
        }
    }

    /** Chegou sugestão: se já há um card aberto, entra na fila; senão abre. */
    private fun present(item: Hub.Item) {
        buzz()
        if (!Settings.canDrawOverlays(this)) {
            showNotification(item)
        } else if (overlay != null) {
            refreshQueue()
        } else {
            showOverlay(item)
        }
    }

    /** Depois de aprovar/descartar: abre a sugestão mais antiga que ainda vale (até 5 min). */
    private fun showNext() {
        val now = System.currentTimeMillis()
        Hub.items.filter { it.status == "pendente" && now - it.time > 300000 }.forEach { Hub.expire(this, it.id) }
        val next = Hub.items.lastOrNull { it.status == "pendente" } ?: return
        showOverlay(next)
    }

    private fun showOverlay(item: Hub.Item) {
        removeOverlay()
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val v = LayoutInflater.from(ContextThemeWrapper(this, R.style.Theme_Copiloto)).inflate(R.layout.overlay_card, null)
        v.findViewById<TextView>(R.id.ovFrom).text = "${item.sender}: ${item.message}"
        v.findViewById<TextView>(R.id.ovReply).text = item.reply
        val et = v.findViewById<EditText>(R.id.ovEditText)
        val tvReply = v.findViewById<TextView>(R.id.ovReply)
        var editing = false
        v.findViewById<Button>(R.id.ovOk).setOnClickListener {
            Hub.approve(this, item.id, if (editing) et.text.toString() else null)
            removeOverlay()
            showNext()
        }
        v.findViewById<Button>(R.id.ovNo).setOnClickListener {
            Hub.discard(this, item.id)
            removeOverlay()
            showNext()
        }
        v.findViewById<Button>(R.id.ovRegen).setOnClickListener {
            Thread {
                val r = Hub.fetch(this, item.sender, item.message, true) ?: return@Thread
                item.reply = r
                main.post {
                    tvReply.text = r
                    if (editing) et.setText(r)
                }
            }.start()
        }
        v.findViewById<Button>(R.id.ovEdit).setOnClickListener {
            editing = true
            tvReply.visibility = View.GONE
            et.setText(item.reply)
            et.visibility = View.VISIBLE
            // Torna a janela focável para o teclado aparecer.
            val p = v.layoutParams as WindowManager.LayoutParams
            p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
            wm.updateViewLayout(v, p)
            et.requestFocus()
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP
        lp.y = (48 * resources.displayMetrics.density).toInt()
        queueTv = v.findViewById(R.id.ovQueue)
        try {
            wm.addView(v, lp)
            overlay = v
            refreshQueue()
        } catch (_: Exception) {
            showNotification(item)
        }
    }

    private fun removeOverlay() {
        overlay?.let {
            try {
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it)
            } catch (_: Exception) {
            }
        }
        overlay = null
    }

    private fun showNotification(item: Hub.Item) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("sug", "Sugestões", NotificationManager.IMPORTANCE_HIGH))
        fun act(a: String, label: String): Notification.Action {
            val i = Intent().setComponent(ComponentName(this, ActionReceiver::class.java))
                .putExtra("id", item.id).putExtra("act", a)
            val pi = PendingIntent.getBroadcast(
                this, notifId(item.id) * 2 + (if (a == "ok") 0 else 1), i,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            return Notification.Action.Builder(null, label, pi).build()
        }
        val nt = Notification.Builder(this, "sug")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(item.sender + ": " + item.message)
            .setContentText(item.reply)
            .setStyle(Notification.BigTextStyle().bigText(item.reply))
            .addAction(act("ok", "APROVAR E ENVIAR"))
            .addAction(act("no", "DESCARTAR"))
            .setAutoCancel(true)
            .build()
        nm.notify(notifId(item.id), nt)
    }

    override fun onDestroy() {
        main.removeCallbacks(updateTick)
        removeOverlay()
        super.onDestroy()
    }
}
