package online.chatsecreto.copiloto

import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import org.json.JSONObject

/**
 * Monta o formulário de configuração a partir da descrição enviada pelo plugin (grupos, campos, tipos).
 * Campos novos no plugin aparecem aqui sem precisar atualizar o app.
 */
class CfgForm(private val c: Context, private val box: LinearLayout) {

    private val getters = LinkedHashMap<String, () -> Any?>()
    private val dp = c.resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()

    private val white = 0xFFE6EDF3.toInt()
    private val gray = 0xFF8B949E.toInt()
    private val green = 0xFF00C875.toInt()

    private fun truthy(v: Any?): Boolean = when (v) {
        is Boolean -> v
        is Number -> v.toInt() != 0
        is String -> v == "1" || v.equals("true", true)
        else -> false
    }

    fun build(cfg: JSONObject) {
        box.removeAllViews()
        getters.clear()
        val values = cfg.getJSONObject("values")
        val secretSet = cfg.optBoolean("secret_set")
        val groups = cfg.getJSONArray("groups")
        for (gi in 0 until groups.length()) {
            val g = groups.getJSONObject(gi)
            val card = LinearLayout(c)
            card.orientation = LinearLayout.VERTICAL
            card.setBackgroundResource(R.drawable.card)
            card.setPadding(px(14), px(12), px(14), px(14))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = px(12)
            card.layoutParams = lp

            val title = TextView(c)
            title.text = g.getString("title").uppercase()
            title.setTextColor(green)
            title.textSize = 12f
            title.setTypeface(null, Typeface.BOLD)
            title.setPadding(0, 0, 0, px(6))
            card.addView(title)

            val fields = g.getJSONArray("fields")
            for (fi in 0 until fields.length()) addField(card, fields.getJSONObject(fi), values, secretSet)
            box.addView(card)
        }
    }

    private fun label(card: LinearLayout, text: String, hint: String) {
        val l = TextView(c)
        l.text = text
        l.setTextColor(white)
        l.textSize = 13f
        l.setPadding(0, px(10), 0, 0)
        card.addView(l)
        if (hint.isNotBlank()) {
            val h = TextView(c)
            h.text = hint
            h.setTextColor(gray)
            h.textSize = 11f
            card.addView(h)
        }
    }

    private fun addField(card: LinearLayout, f: JSONObject, values: JSONObject, secretSet: Boolean) {
        val key = f.getString("key")
        val type = f.getString("type")
        val lab = f.getString("label")
        val hint = f.optString("hint")
        val cur = values.opt(key)

        when (type) {
            "check" -> {
                val sw = Switch(c)
                sw.text = lab
                sw.setTextColor(white)
                sw.isChecked = truthy(cur)
                sw.setPadding(0, px(8), 0, 0)
                card.addView(sw)
                if (hint.isNotBlank()) {
                    val h = TextView(c)
                    h.text = hint
                    h.setTextColor(gray)
                    h.textSize = 11f
                    card.addView(h)
                }
                getters[key] = { sw.isChecked }
            }
            "select" -> {
                label(card, lab, hint)
                val opts = f.getJSONObject("options")
                val keys = mutableListOf<String>()
                val names = mutableListOf<String>()
                val it = opts.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    keys.add(k)
                    names.add(opts.getString(k))
                }
                val sp = Spinner(c)
                val ad = object : ArrayAdapter<String>(c, android.R.layout.simple_spinner_item, names) {
                    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                        val v = super.getView(position, convertView, parent) as TextView
                        v.setTextColor(white)
                        return v
                    }

                    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                        val v = super.getDropDownView(position, convertView, parent) as TextView
                        v.setTextColor(0xFF111111.toInt())
                        return v
                    }
                }
                ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                sp.adapter = ad
                val idx = keys.indexOf(cur?.toString() ?: "")
                sp.setSelection(if (idx >= 0) idx else 0)
                card.addView(sp)
                getters[key] = { keys[sp.selectedItemPosition] }
            }
            else -> { // text, area, int, secret
                label(card, lab, hint)
                val et = EditText(c)
                et.setTextColor(white)
                et.setHintTextColor(0xFF6E7681.toInt())
                et.textSize = 14f
                when (type) {
                    "area" -> {
                        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                        et.minLines = 3
                        et.gravity = android.view.Gravity.TOP
                        et.setText(cur?.toString() ?: "")
                    }
                    "int" -> {
                        et.inputType = InputType.TYPE_CLASS_NUMBER
                        et.setText(cur?.toString() ?: "")
                    }
                    "secret" -> {
                        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                        et.hint = if (secretSet) "(salva — deixe vazio para manter)" else "cole a chave aqui"
                    }
                    else -> {
                        et.inputType = InputType.TYPE_CLASS_TEXT
                        et.setText(cur?.toString() ?: "")
                    }
                }
                card.addView(et)
                getters[key] = {
                    val t = et.text.toString()
                    when (type) {
                        "int" -> t.trim().toIntOrNull()
                        "secret" -> if (t.isBlank()) null else t.trim()
                        else -> t
                    }
                }
            }
        }
    }

    /** Valores atuais da tela, prontos para enviar ao plugin. */
    fun collect(): JSONObject {
        val o = JSONObject()
        for ((k, g) in getters) {
            val v = g() ?: continue
            o.put(k, v)
        }
        return o
    }

    val loaded: Boolean get() = getters.isNotEmpty()
}
