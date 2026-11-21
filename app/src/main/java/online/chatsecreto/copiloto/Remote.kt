package online.chatsecreto.copiloto

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Configuração remota: lê e grava no plugin do WordPress (mesmo token da captação). */
object Remote {

    class Result(val json: JSONObject?, val error: String?)

    private fun url(c: Context, route: String) = Prefs.url(c).replace("rando-webhook", route)

    private fun call(c: Context, route: String, method: String, body: JSONObject?): Result {
        return try {
            val conn = URL(url(c, route)).openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = 15000
            conn.readTimeout = 25000
            conn.setRequestProperty("X-Rando-Token", Prefs.token(c))
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            if (code == 401 || code == 403) return Result(null, "Token incorreto")
            if (code == 404) return Result(null, "Plugin não encontrado — atualize o plugin no WordPress (versão 1.3.0 ou mais nova)")
            if (code != 200) return Result(null, "Erro $code")
            val txt = conn.inputStream.bufferedReader().use { it.readText() }
            Result(JSONObject(txt), null)
        } catch (e: Exception) {
            Result(null, "Sem conexão (${e.javaClass.simpleName})")
        }
    }

    /** Campos (com rótulos), valores atuais, números e leads recentes. */
    fun get(c: Context): Result = call(c, "rando-config", "GET", null)

    /** Salva só os valores enviados e devolve a configuração atualizada. */
    fun save(c: Context, values: JSONObject): Result =
        call(c, "rando-config", "POST", JSONObject().put("values", values))

    /** purge | regen_token */
    fun admin(c: Context, action: String): Result =
        call(c, "rando-admin", "POST", JSONObject().put("action", action))
}
