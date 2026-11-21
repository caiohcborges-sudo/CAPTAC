package online.chatsecreto.copiloto

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Envio pela tela, SÓ para responder a uma mensagem recebida quando a notificação não tem resposta rápida.
 * Só age dentro do app alvo, por até 30 s depois de abrir o chat da mensagem, e nada mais:
 * não inicia conversas, não pula chats.
 */
class ReplyService : AccessibilityService() {

    companion object {
        fun isEnabled(c: Context): Boolean {
            val v = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return v.contains(c.packageName + "/" + ReplyService::class.java.name) || v.contains(c.packageName + "/." + ReplyService::class.java.simpleName)
        }
    }

    private var lastTry = 0L
    private var dumped = false

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        val s = Hub.screen ?: return
        val now = System.currentTimeMillis()
        if (now > s.until) {
            Hub.screen = null
            Hub.setStatus(this, s.id, "falhou (tela): chat não abriu a tempo")
            return
        }
        if (!(e.packageName?.toString() ?: "").equals(s.pkg, ignoreCase = true)) return
        if (now - lastTry < 500) return
        lastTry = now

        val root = rootInActiveWindow ?: return
        val field = findEditable(root) ?: return
        if (field.text?.toString() != s.text) {
            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, s.text)
            if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return
        }
        val btn = findSend(root)
        if (btn != null && btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Hub.screen = null
            Hub.setStatus(this, s.id, "enviada (pela tela)")
            dumped = false
        } else if (!dumped) {
            dumped = true
            Hub.diag("Texto colocado, mas não achei o botão de enviar. Botões na tela: " + clickables(root))
        }
    }

    private fun findEditable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.isEditable || (n.className?.toString() ?: "").contains("EditText")) return n
        for (i in 0 until n.childCount) findEditable(n.getChild(i))?.let { return it }
        return null
    }

    private val sendRe = Regex("enviar|send|mandar|submit", RegexOption.IGNORE_CASE)

    private fun findSend(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        val label = (n.contentDescription?.toString() ?: "") + " " + (n.text?.toString() ?: "") + " " + (n.viewIdResourceName ?: "")
        if ((n.isClickable || n.isEnabled) && sendRe.containsMatchIn(label) && !n.isEditable) return n
        for (i in 0 until n.childCount) findSend(n.getChild(i))?.let { return it }
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

    override fun onInterrupt() {}
}
