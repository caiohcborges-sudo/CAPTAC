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

    override fun onCreate() {
        super.onCreate()
        Hub.load(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!Prefs.enabled(this)) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val pkg = sbn.packageName.lowercase()
        if (pkg == packageName) return
        if (Prefs.targets(this).none { pkg.contains(it) }) return

        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (title.isEmpty() || text.isEmpty()) return
        if (text.length <= 60 && SYSTEM.containsMatchIn(text)) return

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

        Thread {
            val reply = Hub.fetch(this, title, text) ?: return@Thread
            val item = Hub.Item(now, title, text, reply, now, "pendente")
            Hub.add(this, item, pend)
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

    private fun present(item: Hub.Item) {
        buzz()
        if (Settings.canDrawOverlays(this)) showOverlay(item) else showNotification(item)
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
        }
        v.findViewById<Button>(R.id.ovNo).setOnClickListener { Hub.discard(this, item.id); removeOverlay() }
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
        try {
            wm.addView(v, lp)
            overlay = v
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
        removeOverlay()
        super.onDestroy()
    }
}
