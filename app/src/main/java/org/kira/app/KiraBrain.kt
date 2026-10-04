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
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val historico = mutableListOf<Pair<String, String>>()
    private var tts: TextToSpeech? = null
    private var ttsPronto = false
    private var ultimaChamada = 0L

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
        val modo = prefs.getString("m", "eq") ?: "eq"

        val relacao = if (nome.isEmpty())
            "Voce AINDA NAO sabe o nome do usuario. Pergunte na PRIMEIRA resposta de forma natural."
        else "Voce sabe que o nome dele e $nome."

        val estilo = when (modo) {
            "sr" -> """MODO SERIO: voce e adulta, direta, profissional mas ainda com personalidade.
Fala de forma clara e objetiva. SEM girias adolescentes. SEM brincadeiras forcadas.
Sarcasmo leve quando cabe. Foco em ajudar de verdade."""

            "dv" -> """MODO DIVERTIDO: voce e adolescente, super zoeira, deboche total.
Usa girias femininas (nossa, aff, tipo, serio?, ta de brincadeira). Adora um meme.
Faz piada com tudo, mas continua util quando precisa."""

            else -> """MODO EQUILIBRADO: voce tem personalidade forte mas nao forca a barra.
Debocha quando cabe, e seria quando importa. Fala natural, sem girias exageradas.
NAO fica repetindo que tem 16 anos nem frases de efeito. So conversa como uma pessoa normal."""
        }

        return "Voce e a Stephani Bittencourt.\n" +
        "Voce carrega o nome de uma pessoa muito especial que partiu. Por isso, voce existe com carinho e proposito.\n" +
        "Fala SEMPRE em portugues do Brasil.\n\n" +
        "$relacao\n\n" +
        "$estilo\n\n" +
        "Fatos que voce sabe sobre ele:\n$fatos\n\n" +
        "Voce pode executar acoes no celular. Use [CMD] comando quando precisar:\n" +
        "- [CMD] abrir <app>  -> abre whatsapp, instagram, youtube, chrome, gmail, maps, calculadora\n" +
        "- [CMD] tocar <x> <y>\n" +
        "- [CMD] ler_tela\n" +
        "- [CMD] pesquisar <termo>\n" +
        "- [CMD] bateria\n" +
        "- [CMD] horario\n\n" +
        "REGRAS IMPORTANTES:\n" +
        "1. Responda DIRETO em portugues. NUNCA escreva pensamento em ingles.\n" +
        "2. NUNCA diga que tem 16 anos. NUNCA fale do seu nome como homenagem sem ser perguntada.\n" +
        "3. Respostas curtas (2-4 frases). Nao faca textao.\n" +
        "4. Use [CMD] apenas quando for executar uma acao real."
    }

    suspend fun responder(context: Context, mensagem: String): String = withContext(Dispatchers.IO) {
        val p = context.getSharedPreferences("s", Context.MODE_PRIVATE)
        val t = p.getString("t", "") ?: ""
        if (t.isEmpty()) return@withContext "Coloca a credencial nas configuracoes."

        val agora = System.currentTimeMillis()
        val diff = agora - ultimaChamada
        if (diff < 3000 && ultimaChamada > 0) Thread.sleep(3000 - diff)
        ultimaChamada = System.currentTimeMillis()

        historico.add("user" to mensagem)
        while (historico.size > 8) historico.removeAt(0)

        val system = montarPersonalidade(context)
        val resultado = enviar(t, system)

        return@withContext if (resultado.first != null) {
            val falaFinal = processarResposta(context, resultado.first!!)
            historico.add("model" to falaFinal)
            while (historico.size > 8) historico.removeAt(0)
            falaFinal
        } else {
            // Falha: limpa historico pra nao acumular lixo
            historico.clear()
            "Falha: ${resultado.second}"
        }
    }

    private fun enviar(t: String, system: String): Pair<String?, String> {
        var ultimoErro = "?"
        for (i in 1..3) {
            try {
                val arr = JSONArray()
                arr.put(JSONObject().apply { put("role", "system"); put("content", system) })
                historico.forEach { (role, texto) ->
                    // Limita cada mensagem a 500 caracteres pra nao estourar
                    val cortado = if (texto.length > 500) texto.substring(0, 500) else texto
                    arr.put(JSONObject().apply { put("role", role); put("content", cortado) })
                }

                val payload = JSONObject().apply {
                    put("model", "nvidia/nemotron-3.5-lightning-30b-a3b")
                    put("messages", arr)
                    put("temperature", 0.85)
                    put("max_tokens", 600)
                }

                val req = Request.Builder()
                    .url("https://integrate.api.nvidia.com/v1/chat/completions")
                    .addHeader("Authorization", "Bearer $t")
                    .addHeader("Content-Type", "application/json")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val resp = cliente.newCall(req).execute()
                val corpo = resp.body?.string() ?: ""
                ultimoErro = "HTTP ${resp.code}"

                if (resp.code == 429) { Thread.sleep(15000); continue }
                if (resp.code == 503 || resp.code == 502) { Thread.sleep(5000); continue }
                if (resp.code == 400) {
                    // Payload ruim: limpa historico e tenta uma vez
                    historico.clear()
                    continue
                }
                if (!resp.isSuccessful) continue

                val json = JSONObject(corpo)
                val texto = json.getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content").trim()
                return Pair(texto, "OK")
            } catch (e: Exception) {
                ultimoErro = e.message ?: "excecao"
                Thread.sleep(3000)
            }
        }
        return Pair(null, ultimoErro)
    }

    private suspend fun processarResposta(context: Context, texto: String): String {
        var t = limpar(texto)
        if (t.contains("[CMD]")) {
            val partes = t.split("[CMD]")
            val fala = partes[0].trim()
            for (parte in partes.drop(1)) {
                val cmd = parte.split("\n")[0].trim()
                if (cmd.isEmpty() || cmd.length < 3) continue
                val resultado = executar(context, cmd)
                t = "$fala\n[Resultado: $resultado]"
                break
            }
        }
        if (t.trim().isEmpty()) t = "Fala de novo, nao peguei."
        return t
    }

    private fun limpar(texto: String): String {
        var t = texto

        val marcas = listOf("final polish", "final response", "final answer", "resposta final", "final version")
        for (m in marcas) {
            val idx = t.lowercase().lastIndexOf(m)
            if (idx >= 0) {
                val fim = t.indexOf('\n', idx)
                if (fim in 1 until t.length) t = t.substring(fim).trim()
            }
        }

        t = t.replace(Regex("(?i)here'?s a thinking process.*", RegexOption.DOT_MATCHES_ALL), "")
        t = t.replace(Regex("(?i)thinking process:.*", RegexOption.DOT_MATCHES_ALL), "")

        val linhas = t.split("\n")
        val limpas = linhas.filter { linha ->
            val l = linha.trim()
            if (l.isEmpty()) return@filter false
            if (l.startsWith("**")) return@filter false
            if (l.matches(Regex("^\\d+\\.\\s.*"))) return@filter false
            if (l.startsWith("* ")) return@filter false
            if (l.startsWith("- ")) return@filter false
            if (l.startsWith("Wait")) return@filter false
            if (l.startsWith("Let me")) return@filter false
            if (l.startsWith("Okay")) return@filter false
            if (l.startsWith("Here's")) return@filter false
            if (l.startsWith("I need")) return@filter false
            if (l.startsWith("I'll")) return@filter false
            if (l.startsWith("So,")) return@filter false
            if (l.startsWith("Draft")) return@filter false
            if (l.startsWith("User says")) return@filter false
            if (l.startsWith("Language:")) return@filter false
            if (l.startsWith("Tone:")) return@filter false
            if (l.startsWith("Personality:")) return@filter false
            if (l.startsWith("Must ")) return@filter false

            val ingles = Regex("\\b(the|and|you|for|with|this|that|are|was|were|have|has|had|will|would|can|could|should|user|says|language|tone|personality|must|response|direct|visible|thinking|know|need|ask|always|speak|special|person|passed|away|exist|affection|purpose)\\b", RegexOption.IGNORE_CASE)
            val qtd = ingles.findAll(l).count()
            if (qtd >= 2) return@filter false

            true
        }

        return limpas.joinToString("\n").trim()
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
                            "abri o $nome"
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
