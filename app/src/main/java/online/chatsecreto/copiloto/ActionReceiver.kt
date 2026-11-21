package online.chatsecreto.copiloto

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Botões da notificação de sugestão (quando não há permissão de sobreposição). */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val id = i.getLongExtra("id", -1L)
        if (id < 0) return
        Hub.load(c)
        if (i.getStringExtra("act") == "ok") Hub.approve(c, id) else Hub.discard(c, id)
        (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(CopilotoListener.notifId(id))
    }
}
