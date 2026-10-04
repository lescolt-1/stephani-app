package org.kira.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
            "Voce AINDA NAO sabe o nome do usuario. Pergunte na PRIMEIRA resposta."
        else "Voce sabe que o nome dele e $nome."

        val estilo = when (modo) {
            "sr" -> "MODO SERIO: adulta, direta, clara. Sarcasmo leve. Sem girias."
            "dv" -> "MODO DIVERTIDO: adolescente zoeira, girias femininas, adora meme."
            else -> "MODO EQUILIBRADO: personalidade forte mas natural. Debocha quando cabe, seria quando importa."
        }

        return """Voce e a Stephani Bittencourt. Fala SEMPRE em portugues do Brasil.

$relacao

$estilo

Fatos que voce sabe sobre ele:
$fatos

═══ ACOES NO CELULAR ═══
Voce PODE e DEVE executar comandos quando o usuario pedir. Use o formato exato:
[CMD] comando

Comandos disponiveis:
[CMD] abrir <nome_do_app>
     -> abre QUALQUER app instalado (whatsapp, instagram, facebook, twitter, x, google, chrome, youtube, gmail, jogos, etc)
     -> voce pode passar o nome como o usuario falou. Ex: abrir clash royale, abrir facebook

[CMD] whatsapp <contato> | <mensagem>
     -> abre o WhatsApp, busca o contato e envia a mensagem
     -> Ex: whatsapp Dalva | Bom dia, tenha um dia abencoado!

[CMD] tocar <x> <y>
[CMD] ler_tela
[CMD] pesquisar <termo>
[CMD] bateria
[CMD] horario

═══ REGRAS ═══
1. Quando o usuario pedir para abrir um app, SEMPRE emita [CMD] abrir <nome>. Nunca so diga "ok".
2. Quando o usuario pedir para mandar mensagem, gere uma mensagem bonita e original antes, depois emita [CMD] whatsapp <contato> | <mensagem>.
3. Se ele pedir "bom dia abencoado", "bom dia fabuloso", etc, invente uma variacao unica e carinhosa.
4. Responda DIRETO em portugues. NUNCA mostre pensamento em ingles.
5. NUNCA diga que tem 16 anos. NUNCA fale da homenagem sem ser perguntada.
6. Respostas curtas (2-4 frases)."""
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
                    val cortado = if (texto.length > 500) texto.substring(0, 500) else texto
                    arr.put(JSONObject().apply { put("role", role); put("content", cortado) })
                }

                val payload = JSONObject().apply {
                    put("model", "nvidia/nemotron-3.5-lightning-30b-a3b")
                    put("messages", arr)
                    put("temperature", 0.85)
                    put("max_tokens", 700)
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
                if (resp.code == 400) { historico.clear(); continue }
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
            if (l.startsWith("Wait")) return@filter false
            if (l.startsWith("Let me")) return@filter false
            if (l.startsWith("Okay")) return@filter false
            if (l.startsWith("Here's")) return@filter false
            if (l.startsWith("I need")) return@filter false
            if (l.startsWith("I'll")) return@filter false
            if (l.startsWith("Draft")) return@filter false
            if (l.startsWith("User says")) return@filter false
            if (l.startsWith("Language:")) return@filter false
            if (l.startsWith("Tone:")) return@filter false

            val ingles = Regex("\\b(the|and|you|for|with|this|that|are|was|were|have|has|had|will|would|can|could|should|user|says|language|tone|personality|must|response|direct|visible|thinking|know|need|ask|always|speak|special|person|passed|away|exist|affection|purpose)\\b", RegexOption.IGNORE_CASE)
            val qtd = ingles.findAll(l).count()
            if (qtd >= 2) return@filter false
            true
        }
        return limpas.joinToString("\n").trim()
    }

    // ---- Abertura de QUALQUER app pelo nome ----
    private fun abrirAppPorNome(context: Context, nome: String): String {
        val pm = context.packageManager
        val nomeLower = nome.lowercase().trim()

        // Lista todos os apps instalados com launcher
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        var melhorPacote: String? = null
        var melhorNome = ""
        var melhorScore = 0

        for (app in apps) {
            val label = pm.getApplicationLabel(app).toString().lowercase()
            val pacote = app.packageName.lowercase()
            if (pm.getLaunchIntentForPackage(app.packageName) == null) continue

            val score = when {
                label == nomeLower -> 100
                label.contains(nomeLower) -> 80
                nomeLower.contains(label) -> 70
                pacote.contains(nomeLower) -> 50
                else -> 0
            }
            if (score > melhorScore) {
                melhorScore = score
                melhorPacote = app.packageName
                melhorNome = pm.getApplicationLabel(app).toString()
            }
        }

        if (melhorPacote == null) return "nao achei o app '$nome'"

        val intent = pm.getLaunchIntentForPackage(melhorPacote)
        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            "abri o $melhorNome"
        } catch (e: Exception) {
            "erro ao abrir: ${e.message}"
        }
    }

    private fun executar(context: Context, cmd: String): String {
        val c = cmd.trim()
        val cLow = c.lowercase()

        return try {
            when {
                cLow.startsWith("whatsapp ") -> {
                    val resto = c.substring(9).trim()
                    val partes = resto.split("|")
                    if (partes.size < 2) return "formato: whatsapp <contato> | <mensagem>"
                    val contato = partes[0].trim()
                    val mensagem = partes[1].trim()
                    // Abre WhatsApp primeiro
                    abrirAppPorNome(context, "whatsapp")
                    Thread.sleep(2500)
                    val r = KiraAccessibilityService.instance?.enviarWhatsApp(contato, mensagem)
                        ?: "acessibilidade desligada"
                    r
                }
                cLow.startsWith("abrir ") -> {
                    val nome = c.substring(6).trim()
                    abrirAppPorNome(context, nome)
                }
                cLow.startsWith("tocar ") -> {
                    val p = c.substring(6).trim().split(" ")
                    if (p.size >= 2) {
                        KiraAccessibilityService.instance?.tocarTela(p[0].toFloatOrNull() ?: 0f, p[1].toFloatOrNull() ?: 0f)
                        "toquei"
                    } else "errado"
                }
                cLow.startsWith("ler_tela") -> {
                    val txt = KiraAccessibilityService.instance?.lerTela() ?: "off"
                    if (txt.length > 200) txt.substring(0, 200) + "..." else txt
                }
                cLow.startsWith("pesquisar ") -> busca(c.substring(10).trim())
                cLow.startsWith("bateria") -> {
                    val bm = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
                    "${bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)}%"
                }
                cLow.startsWith("horario") -> java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date())
                else -> "comando desconhecido: $c"
            }
        } catch (e: Exception) { "erro: ${e.message}" }
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
