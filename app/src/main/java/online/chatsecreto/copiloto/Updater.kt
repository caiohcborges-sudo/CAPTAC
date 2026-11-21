package online.chatsecreto.copiloto

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Atualização pelo GitHub Releases: o CI publica "build-N"; o app compara N com o próprio versionCode. */
object Updater {

    private const val API = "https://api.github.com/repos/caiohcborges-sudo/CAPTAC/releases/latest"

    class Info(val build: Int, val url: String)

    fun currentBuild(c: Context): Int =
        c.packageManager.getPackageInfo(c.packageName, 0).longVersionCode.toInt()

    /** Devolve a release mais nova, se for maior que a instalada. Chamar fora da thread principal. */
    fun check(c: Context): Info? {
        return try {
            val conn = URL(API).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            if (conn.responseCode != 200) return null
            val j = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val n = j.getString("tag_name").removePrefix("build-").toIntOrNull() ?: return null
            val assets = j.getJSONArray("assets")
            var url: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk")) url = a.getString("browser_download_url")
            }
            if (url != null && n > currentBuild(c)) Info(n, url) else null
        } catch (_: Exception) {
            null
        }
    }

    /** Baixa o APK e abre o instalador do Android (o usuário confirma). Fora da thread principal. */
    fun downloadAndInstall(c: Context, info: Info, onError: (String) -> Unit) {
        try {
            if (!c.packageManager.canRequestPackageInstalls()) {
                c.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                onError("Permita 'instalar apps desconhecidos' para o Copiloto e toque em Atualizar de novo.")
                return
            }
            val dir = File(c.cacheDir, "update").apply { mkdirs() }
            val f = File(dir, "app.apk")
            val conn = URL(info.url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.inputStream.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            val uri = FileProvider.getUriForFile(c, c.packageName + ".fileprovider", f)
            c.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            onError("Falha ao baixar a atualização: ${e.javaClass.simpleName}")
        }
    }

    /** Aviso fora do app: notificação "Atualização disponível". */
    fun notifyAvailable(c: Context, info: Info) {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("upd", "Atualizações", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            c, 7, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = android.app.Notification.Builder(c, "upd")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Atualização disponível (build ${info.build})")
            .setContentText("Toque para abrir o Copiloto e atualizar.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(7001, n)
    }
}
