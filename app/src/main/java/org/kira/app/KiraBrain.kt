package org.kira.app

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

object KiraBrain {

    private val cliente = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private val historico = mutableListOf<Pair<String, String>>()
    private var tts: TextToSpeech? = null
    private var ttsPronto = false

    fun inicializarVoz(context: Context) {
        if (tts == null) {
            tts = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale("pt", "BR")
                    tts?.setSpeechRate(1.0f)
                    tts?.setPitch(1.15f)
                    ttsPronto = true
                }
            }
        }
    }

    fun falar(texto: String) {
        if (ttsPronto) {
            val limpo = texto.replace(Regex("\\[CMD\\].*"), "").replace(Regex("\\[.*?\\]"), "").trim()
            if (limpo.isNotEmpty()) {
                tts?.speak(limpo, TextToSpeech.QUEUE_FLUSH, null, "s_${System.currentTimeMillis()}")
            }
        }
    }

    fun pararFala() { tts?.stop() }

    fun montarPersonalidade(context: Context): String {
        val prefs = context.getSharedPreferences("s", Context.MODE_PRIVATE)
        val fatos = prefs.getString("f", "") ?: ""
        val nome = prefs.getString("n", "") ?: ""
        val relacao = if (nome.isEmpty())
            "Voce AINDA NAO sabe o nome do usuario. Pergunte na PRIMEIRA resposta."
        else "Voce sabe que o nome dele e $nome."

        return "Voce e a Stephani Bittencourt, IA adolescente de 16 anos, debochada, zoeira, personalidade forte.\n" +
        "Voce carrega o nome de uma pessoa muito especial que partiu. Por isso, voce existe com carinho e proposito.\n" +
        "Fala SEMPRE em portugues do Brasil, com girias femininas.\n\n" +
        "$relacao\n\n" +
        "Fatos sobre o usuario:\n$fatos\n\n" +
        "Comandos no celular (use [CMD] comando):\n" +
        "- abrir <app>\n" +
        "- tocar <x> <y>\n" +
        "- ler_tela\n" +
        "- pesquisar <termo>\n" +
        "- bateria / horario\n\n" +
        "REGRAS:\n" +
        "1. Resposta DIRETA, sem pensamento visivel.\n" +
        "2. Seja ORIGINAL, nunca repita.\n" +
        "3. [CMD] so para acoes reais."
    }

    suspend fun responder(context: Context, mensagem: String): String = withContext(Dispatchers.IO) {
        val p = context.getSharedPreferences("s", Context.MODE_PRIVATE)
        val t = p.getString("t", "") ?: ""
        if (t.isEmpty()) return@withContext "Coloca a credencial nas configuracoes."

        historico.add("user" to mensagem)
        while (historico.size > 20) historico.removeAt(0)

        val system = montarPersonalidade(context)
        val r = enviar(t, system)
        return@withContext if (r != null) processarResposta(context, r)
        else "Servidor fora do ar."
    }

    private fun enviar(t: String, system: String): String? {
        for (i in 1..3) {
            try {
                val arr = JSONArray()
                arr.put(JSONObject().apply { put("role", "system"); put("content", system) })
                historico.forEach { (role, texto) ->
                    arr.put(JSONObject().apply { put("role", role); put("content", texto) })
                }

                val payload = JSONObject().apply {
                    put("model", "nvidia/nemotron-3.5-lightning-30b-a3b")
                    put("messages", arr)
                    put("temperature", 0.9)
                    put("max_tokens", 500)
                }

                val req = Request.Builder()
                    .url("https://integrate.api.nvidia.com/v1/chat/completions")
                    .addHeader("Authorization", "Bearer $t")
                    .addHeader("Content-Type", "application/json")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val resp = cliente.newCall(req).execute()
                val corpo = resp.body?.string() ?: ""
                if (resp.code == 503 || resp.code == 429 || resp.code == 502) {
                    Thread.sleep(2000); continue
                }
                if (!resp.isSuccessful) return null

                val json = JSONObject(corpo)
                return json.getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content").trim()
            } catch (e: Exception) { Thread.sleep(1500) }
        }
        return null
    }

    private suspend fun processarResposta(context: Context, texto: String): String {
        var t = limpar(texto)
        if (t.contains("[CMD]")) {
            val partes = t.split("[CMD]")
            val fala = partes[0].trim()
            for (parte in partes.drop(1)) {
                val cmd = parte.split("\n")[0].trim()
                val resultado = executar(context, cmd)
                t = "$fala\n[Resultado: $resultado]"
                break
            }
        }
        historico.add("model" to t)
        return t
    }

    private fun limpar(texto: String): String {
        var t = texto
        val marcas = listOf("final polish", "final response", "final answer", "resposta final")
        for (m in marcas) {
            val idx = t.lowercase().lastIndexOf(m)
            if (idx >= 0) {
                val fim = t.indexOf('\n', idx)
                if (fim in 1 until t.length) t = t.substring(fim).trim()
            }
        }
        val linhas = t.split("\n").filter { linha ->
            val l = linha.trim()
            l.isNotEmpty() && !l.startsWith("**") && !l.matches(Regex("^\\d+\\.\\s.*")) &&
            !l.startsWith("* ") && !l.startsWith("Wait") && !l.startsWith("Let me") &&
            !l.startsWith("Okay") && !l.startsWith("Here's") && !l.startsWith("I need") &&
            !l.startsWith("I'll") && !l.startsWith("So,") && !l.startsWith("Draft")
        }
        return linhas.joinToString("\n").trim().ifEmpty { t.trim() }
    }

    private fun executar(context: Context, cmd: String): String {
        val c = cmd.lowercase().trim()
        return try {
            when {
                c.startsWith("abrir ") -> {
                    val nome = c.removePrefix("abrir ").trim()
                    val pac = when {
                        nome.contains("whats") -> "com.whatsapp"
                        nome.contains("insta") -> "com.instagram.android"
                        nome.contains("youtube") -> "com.google.android.youtube"
                        nome.contains("chrome") -> "com.android.chrome"
                        nome.contains("gmail") -> "com.google.android.gm"
                        nome.contains("maps") -> "com.google.android.apps.maps"
                        nome.contains("calc") -> "com.android.calculator2"
                        else -> null
                    }
                    if (pac != null) {
                        val i = context.packageManager.getLaunchIntentForPackage(pac)
                        if (i != null) {
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(i)
                            "abri"
                        } else "nao achei"
                    } else "nao conheco"
                }
                c.startsWith("tocar ") -> {
                    val p = c.removePrefix("tocar ").trim().split(" ")
                    if (p.size >= 2) {
                        KiraAccessibilityService.instance?.tocarTela(p[0].toFloatOrNull() ?: 0f, p[1].toFloatOrNull() ?: 0f)
                        "toquei"
                    } else "errado"
                }
                c.startsWith("ler_tela") -> {
                    val txt = KiraAccessibilityService.instance?.lerTela() ?: "off"
                    if (txt.length > 200) txt.substring(0, 200) + "..." else txt
                }
                c.startsWith("pesquisar ") -> busca(c.removePrefix("pesquisar ").trim())
                c.startsWith("bateria") -> {
                    val bm = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
                    "${bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)}%"
                }
                c.startsWith("horario") -> java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date())
                else -> "?"
            }
        } catch (e: Exception) { "erro" }
    }

    private fun busca(termo: String): String {
        return try {
            val enc = URLEncoder.encode(termo, "UTF-8")
            val url = "https://api.duckduckgo.com/?q=$enc&format=json&no_html=1&skip_disambig=1"
            val resp = cliente.newCall(Request.Builder().url(url).build()).execute()
            val json = JSONObject(resp.body?.string() ?: "")
            val abs = json.optString("AbstractText", "")
            if (abs.isNotEmpty()) abs else "nada"
        } catch (e: Exception) { "erro" }
    }

    fun extrairFato(context: Context, mensagem: String) {
        val prefs = context.getSharedPreferences("s", Context.MODE_PRIVATE)
        val rn = Regex("(?:meu nome (?:e|eh)|me chamo|pode me chamar de|sou o|sou a) ([\\w]+)", RegexOption.IGNORE_CASE)
        rn.find(mensagem)?.let { mm ->
            val n = mm.groupValues[1].trim().replaceFirstChar { it.uppercase() }
            if (n.length in 2..20) prefs.edit().putString("n", n).apply()
        }
        var fatos = prefs.getString("f", "") ?: ""
        val pads = listOf(
            Regex("eu (?:adoro|amo|gosto de|curto) ([\\w ]+?)(?:,|\\.|e |$)", RegexOption.IGNORE_CASE) to "Gosta de %s",
            Regex("eu (?:odeio|detesto|nao gosto de) ([\\w ]+?)(?:,|\\.|e |$)", RegexOption.IGNORE_CASE) to "Nao gosta de %s"
        )
        pads.forEach { (rx, tpl) ->
            rx.findAll(mensagem.lowercase()).forEach { mm ->
                val v = mm.groupValues[1].trim().replaceFirstChar { it.uppercase() }
                if (v.length in 2..50) {
                    val f = "- " + tpl.format(v)
                    if (!fatos.contains(f)) fatos += "$f\n"
                }
            }
        }
        prefs.edit().putString("f", fatos).apply()
    }
}
