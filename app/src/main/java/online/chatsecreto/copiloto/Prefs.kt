package online.chatsecreto.copiloto

import android.content.Context

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    fun url(c: Context) = sp(c).getString("url", "https://chatsecreto.online/wp-json/chatsecreto/v1/rando-webhook")!!
    fun token(c: Context) = sp(c).getString("token", "")!!
    fun pkg(c: Context): String {
        val v = sp(c).getString("pkg", "com.random.chat.app")!!
        // Versões antigas salvaram "randochat", que não é o nome real do pacote.
        return if (v.trim().equals("randochat", true)) "com.random.chat.app" else v
    }
    fun enabled(c: Context) = sp(c).getBoolean("enabled", false)

    fun targets(c: Context): List<String> =
        pkg(c).split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    fun save(c: Context, url: String, token: String, pkg: String) {
        sp(c).edit().putString("url", url.trim()).putString("token", token.trim()).putString("pkg", pkg.trim()).apply()
    }

    fun auto(c: Context) = sp(c).getBoolean("auto", false)
    fun setAuto(c: Context, v: Boolean) = sp(c).edit().putBoolean("auto", v).apply()

    fun closeAds(c: Context) = sp(c).getBoolean("closeAds", true)
    fun setCloseAds(c: Context, v: Boolean) = sp(c).edit().putBoolean("closeAds", v).apply()

    fun setEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("enabled", v).apply()

    fun loadHistory(c: Context): String = sp(c).getString("hist", "[]")!!
    fun saveHistory(c: Context, json: String) = sp(c).edit().putString("hist", json).apply()
}
