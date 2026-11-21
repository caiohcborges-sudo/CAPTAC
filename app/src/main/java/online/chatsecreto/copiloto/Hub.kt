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

    data class Item(val id: Long, val sender: String, val message: String, var reply: String, val time: Long, var status: String)
    class Pending(val pi: PendingIntent, val ri: RemoteInput)

    val items = CopyOnWriteArrayList<Item>() // mais nova primeiro
    private val pending = ConcurrentHashMap<Long, Pending>()
    @Volatile var onChange: (() -> Unit)? = null

    /** Diagnóstico: o que o app viu e por que ignorou (não persiste). */
    val diagLog = CopyOnWriteArrayList<String>()
    @Volatile var lastPkg: String? = null

    fun diag(s: String) {
        val f = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        diagLog.add(0, "$f $s")
        while (diagLog.size > 25) diagLog.removeAt(diagLog.size - 1)
        onChange?.invoke()
    }
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
    /** Envio do modo automático: tenta o RemoteInput; devolve false se não houver (cai na fila manual). */
    fun autoSend(c: Context, id: Long): Boolean {
        val it = items.firstOrNull { x -> x.id == id } ?: return false
        val p = pending[id] ?: return false
        return try {
            val intent = Intent()
            val b = Bundle()
            b.putCharSequence(p.ri.resultKey, it.reply)
            RemoteInput.addResultsToIntent(arrayOf(p.ri), intent, b)
            p.pi.send(c, 0, intent)
            pending.remove(id)
            it.status = "enviada (auto)"
            persist(c)
            onChange?.invoke()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun approve(c: Context, id: Long, edited: String? = null) {
        val it = items.firstOrNull { x -> x.id == id } ?: return
        val wasEdited = !edited.isNullOrBlank() && edited.trim() != it.reply
        if (!edited.isNullOrBlank()) it.reply = edited.trim()
        feedback(c, if (wasEdited) "edited" else "sent", it.reply)
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

    /** Sugestão antiga demais (o lead já foi embora): sai da fila sem feedback. */
    fun expire(c: Context, id: Long) {
        pending.remove(id)
        items.firstOrNull { x -> x.id == id }?.status = "expirada"
        persist(c)
        onChange?.invoke()
    }

    fun pendingCount(): Int = items.count { x -> x.status == "pendente" }

    fun discard(c: Context, id: Long) {
        pending.remove(id)
        items.firstOrNull { x -> x.id == id }?.status = "descartada"
        feedback(c, "discarded", "")
        persist(c)
        onChange?.invoke()
    }

    /** Teste de conexão: devolve mensagem para mostrar ao usuário. */
    fun ping(c: Context): String {
        return try {
            val u = Prefs.url(c).replace("rando-webhook", "rando-ping")
            val conn = URL(u).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("X-Rando-Token", Prefs.token(c))
            when (conn.responseCode) {
                200 -> "Conectado ✅"
                401, 403 -> "Token incorreto ❌"
                404 -> "Plugin não encontrado (URL errada ou plugin desativado) ❌"
                else -> "Erro ${conn.responseCode} ❌"
            }
        } catch (e: Exception) {
            "Sem conexão: ${e.javaClass.simpleName} ❌"
        }
    }

    /** Devolve a sugestão da API, ou null se a API mandou pular / deu erro. */
    /** Avisa o WordPress o que você fez com a sugestão (métricas e aprendizado de estilo). */
    private fun feedback(c: Context, status: String, text: String) {
        Thread {
            try {
                val u = Prefs.url(c).replace("rando-webhook", "rando-feedback")
                val conn = URL(u).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("X-Rando-Token", Prefs.token(c))
                val body = JSONObject().put("status", status).put("text", text).toString()
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                conn.responseCode
            } catch (_: Exception) {
            }
        }.start()
    }

    fun fetch(c: Context, sender: String, message: String, regen: Boolean = false): String? =
        fetchFull(c, sender, message, regen, false)?.first

    /** Devolve (resposta, delay_seconds) ou null se a API mandou pular / deu erro. */
    fun fetchFull(c: Context, sender: String, message: String, regen: Boolean, auto: Boolean): Pair<String, Int>? {
        return try {
            val conn = URL(Prefs.url(c)).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("X-Rando-Token", Prefs.token(c))
            val jb = JSONObject().put("sender", sender).put("message", message)
            if (regen) jb.put("regen", true)
            if (auto) jb.put("auto", true)
            val body = jb.toString()
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != 200) return null
            val txt = conn.inputStream.bufferedReader().use { it.readText() }
            val j = JSONObject(txt)
            val r = j.optString("reply", "")
            if (j.optBoolean("skip", false) || r.isBlank()) null else Pair(r, j.optInt("delay_seconds", 8))
        } catch (_: Exception) {
            null
        }
    }
}
