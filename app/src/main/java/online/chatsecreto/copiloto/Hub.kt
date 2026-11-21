package online.chatsecreto.copiloto

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Estado em memória: sugestões, ações pendentes da notificação original e chamada à API. */
object Hub {

    data class Item(val id: Long, val sender: String, val message: String, val reply: String, val time: Long, var status: String)
    class Pending(val pi: PendingIntent, val ri: RemoteInput)

    val items = CopyOnWriteArrayList<Item>() // mais nova primeiro
    private val pending = ConcurrentHashMap<Long, Pending>()
    @Volatile var onChange: (() -> Unit)? = null
    private var loaded = false

    @Synchronized
    fun load(c: Context) {
        if (loaded) return
        loaded = true
        try {
            val a = JSONArray(Prefs.loadHistory(c))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                items.add(Item(o.getLong("id"), o.getString("s"), o.getString("m"), o.getString("r"), o.getLong("t"), o.getString("st")))
            }
        } catch (_: Exception) {
        }
    }

    private fun persist(c: Context) {
        val a = JSONArray()
        items.forEach {
            a.put(JSONObject().put("id", it.id).put("s", it.sender).put("m", it.message).put("r", it.reply).put("t", it.time).put("st", it.status))
        }
        Prefs.saveHistory(c, a.toString())
    }

    fun add(c: Context, item: Item, p: Pending?) {
        items.add(0, item)
        while (items.size > 30) {
            val old = items.removeAt(items.size - 1)
            pending.remove(old.id)
        }
        if (p != null) pending[item.id] = p
        persist(c)
        onChange?.invoke()
    }

    /** Só é chamado após o toque do usuário em APROVAR E ENVIAR. */
    fun approve(c: Context, id: Long) {
        val it = items.firstOrNull { x -> x.id == id } ?: return
        val p = pending.remove(id)
        var sent = false
        if (p != null) {
            try {
                val intent = Intent()
                val b = Bundle()
                b.putCharSequence(p.ri.resultKey, it.reply)
                RemoteInput.addResultsToIntent(arrayOf(p.ri), intent, b)
                p.pi.send(c, 0, intent)
                sent = true
            } catch (_: Exception) {
            }
        }
        if (sent) {
            it.status = "enviada"
        } else {
            val cm = c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("resposta", it.reply))
            it.status = "copiada (cole no chat)"
        }
        persist(c)
        onChange?.invoke()
    }

    fun discard(c: Context, id: Long) {
        pending.remove(id)
        items.firstOrNull { x -> x.id == id }?.status = "descartada"
        persist(c)
        onChange?.invoke()
    }

    /** Devolve a sugestão da API, ou null se a API mandou pular / deu erro. */
    fun fetch(c: Context, sender: String, message: String): String? {
        return try {
            val conn = URL(Prefs.url(c)).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("X-Rando-Token", Prefs.token(c))
            val body = JSONObject().put("sender", sender).put("message", message).toString()
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != 200) return null
            val txt = conn.inputStream.bufferedReader().use { it.readText() }
            val j = JSONObject(txt)
            if (j.optBoolean("skip", false)) null else j.optString("reply", "").ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }
}
