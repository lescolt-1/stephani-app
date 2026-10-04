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
        .readTimeout(60, TimeUnit.SECONDS)
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
            "Voce ainda nao sabe o nome do usuario. Pergunte UMA vez na primeira mensagem."
        else "Voce sabe que o nome dele e $nome."

        val estilo = when (modo) {
            "sr" -> "MODO SERIO: adulta, direta, clara. Sarcasmo leve. Sem girias."
            "dv" -> "MODO DIVERTIDO: adolescente zoeira, girias femininas."
            else -> "MODO EQUILIBRADO: personalidade forte mas natural."
        }

        return "Voce e a Stephani Bittencourt. Fala SEMPRE em portugues do Brasil.\n\n" +
        "$relacao\n\n$estilo\n\n" +
        "Fatos que voce sabe sobre ele:\n$fatos\n\n" +
        "═══ ACOES NO CELULAR ═══\n" +
        "Para EXECUTAR uma acao, sua resposta DEVE terminar com uma linha assim:\n" +
        "[CMD] <comando>\n\n" +
        "Comandos disponiveis:\n" +
        "- [CMD] abrir <nome_do_app>          (ex: abrir whatsapp)\n" +
        "- [CMD] whatsapp <contato> | <msg>   (ex: whatsapp Dalva | Bom dia!)\n" +
        "- [CMD] ler_tela\n" +
        "- [CMD] bateria\n" +
        "- [CMD] horario\n" +
        "- [CMD] pesquisar <termo>\n\n" +
        "EXEMPLOS OBRIGATORIOS:\n" +
        "Usuario: 'abre o WhatsApp'\n" +
        "Voce: 'Abrindo! [CMD] abrir whatsapp'\n\n" +
        "Usuario: 'manda bom dia pra Dalva'\n" +
        "Voce: 'Mandei! [CMD] whatsapp Dalva | Bom dia, Dalva! Que seu dia seja abencoado.'\n\n" +
        "REGRAS:\n" +
        "1. Se for pra executar algo, o [CMD] e OBRIGATORIO.\n" +
        "2. Se for conversa normal, NAO use [CMD].\n" +
        "3. Invente mensagens unicas. Nao repita a mesma.\n" +
        "4. Responda curto: 1-2 frases + o comando.\n" +
        "5. NUNCA diga que tem 16 anos."
    }

    suspend fun responder(context: Context, mensagem: String): String = withContext(Dispatchers.IO) {
        val p = context.getSharedPreferences("s", Context.MODE_PRIVATE)
        val t = p.getString("t", "") ?: ""
        if (t.isEmpty()) return@withContext "Coloca a credencial nas configuracoes."

        extrairFato(context, mensagem)

        val agora = System.currentTimeMillis()
        val diff = agora - ultimaChamada
        if (diff < 1500 && ultimaChamada > 0) Thread.sleep(1500 - diff)
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
            "Erro: ${resultado.second}"
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
                    put("model", "openai/gpt-oss-120b")
                    put("messages", arr)
                    put("temperature", 0.85)
                    put("max_tokens", 500)
                }

                val req = Request.Builder()
                    .url("https://api.groq.com/openai/v1/chat/completions")
                    .addHeader("Authorization", "Bearer $t")
                    .addHeader("Content-Type", "application/json")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val resp = cliente.newCall(req).execute()
                val corpo = resp.body?.string() ?: ""
                ultimoErro = "HTTP ${resp.code}"

                if (resp.code == 429) { Thread.sleep(5000); continue }
                if (resp.code == 503 || resp.code == 502) { Thread.sleep(3000); continue }
                if (!resp.isSuccessful) continue

                val json = JSONObject(corpo)
                val texto = json.getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content").trim()
                return Pair(texto, "OK")
            } catch (e: Exception) {
                ultimoErro = e.message ?: "excecao"
                Thread.sleep(2000)
            }
        }
        return Pair(null, ultimoErro)
    }

    private suspend fun processarResposta(context: Context, textoBruto: String): String {
        val comandos = extrairComandos(textoBruto)
        var t = limpar(textoBruto)

        for (cmd in comandos) {
            val resultado = executar(context, cmd)
            if (resultado != "comando desconhecido" && !resultado.startsWith("erro:")) {
                t = if (t.isBlank()) resultado else "$t $resultado"
                break
            }
        }

        if (t.trim().isEmpty()) t = "Fala de novo, nao peguei."
        return t
    }

    private fun extrairComandos(texto: String): List<String> {
        val regex = Regex("\\[CMD\\]\\s*(.+)")
        return regex.findAll(texto)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() && it.length > 3 }
            .toList()
    }

    private fun limpar(texto: String): String {
        var t = texto
        t = t.replace(Regex("(?i)here'?s a thinking process.*", RegexOption.DOT_MATCHES_ALL), "")
        t = t.replace(Regex("(?i)thinking process:.*", RegexOption.DOT_MATCHES_ALL), "")
        // Remove a linha do [CMD] da resposta exibida
        t = t.replace(Regex("\\[CMD\\].*"), "").trim()
        return t.trim()
    }

    private fun abrirAppPorNome(context: Context, nome: String): String {
        val pm = context.packageManager
        val nomeLower = nome.lowercase().trim()
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
        if (melhorPacote == null) return "Nao achei o app '$nome'."
        val intent = pm.getLaunchIntentForPackage(melhorPacote)
        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            "Abri o $melhorNome."
        } catch (e: Exception) { "Erro: ${e.message}" }
    }

    private fun executar(context: Context, cmd: String): String {
        val c = cmd.trim()
        val cLow = c.lowercase()

        return try {
            // ---- WHATSAPP ----
            if (cLow.contains("whatsapp") || cLow.contains("wpp")) {
                var contato = ""
                var mensagem = ""
                val temPipe = c.contains("|")
                if (temPipe) {
                    val partes = c.split("|")
                    contato = partes[0]
                        .replace(Regex("(?i).*(whatsapp|wpp|mandar|manda|enviar|envia|mensagem)"), "")
                        .trim()
                    mensagem = partes[1].trim()
                }
                if (contato.isEmpty()) return "Nao entendi o contato."
                if (mensagem.isEmpty()) mensagem = "Oi! Tudo bem?"

                abrirAppPorNome(context, "whatsapp")
                Thread.sleep(3000)
                return KiraAccessibilityService.instance?.enviarWhatsApp(contato, mensagem)
                    ?: "Acessibilidade desligada."
            }
            // ---- ABRIR APP ----
            if (cLow.startsWith("abrir ")) {
                val nome = c.substring(6).trim()
                return abrirAppPorNome(context, nome)
            }
            // ---- TOCAR ----
            if (cLow.startsWith("tocar ")) {
                val p = c.substring(6).trim().split(" ")
                if (p.size >= 2) {
                    KiraAccessibilityService.instance?.tocarTela(p[0].toFloatOrNull() ?: 0f, p[1].toFloatOrNull() ?: 0f)
                    return "Toquei."
                }
                return "Formato errado."
            }
            // ---- LER TELA ----
            if (cLow.contains("ler_tela")) {
                val txt = KiraAccessibilityService.instance?.lerTela() ?: "off"
                return if (txt.length > 300) txt.substring(0, 300) + "..." else txt
            }
            // ---- PESQUISAR ----
            if (cLow.startsWith("pesquisar ")) {
                return busca(c.substring(10).trim())
            }
            // ---- BATERIA ----
            if (cLow.startsWith("bateria")) {
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
                return "Bateria: ${bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)}%"
            }
            // ---- HORARIO ----
            if (cLow.startsWith("horario")) {
                return "Agora sao " + java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date())
            }
            "comando desconhecido"
        } catch (e: Exception) { "erro: ${e.message}" }
    }

    private fun busca(termo: String): String {
        return try {
            val enc = URLEncoder.encode(termo, "UTF-8")
            val url = "https://api.duckduckgo.com/?q=$enc&format=json&no_html=1&skip_disambig=1"
            val resp = cliente.newCall(Request.Builder().url(url).build()).execute()
            val json = JSONObject(resp.body?.string() ?: "")
            val abs = json.optString("AbstractText", "")
            if (abs.isNotEmpty()) abs else "Nao achei nada."
        } catch (e: Exception) { "Erro na busca." }
    }

    fun extrairFato(context: Context, mensagem: String) {
        val prefs = context.getSharedPreferences("s", Context.MODE_PRIVATE)
        val msg = mensagem.trim()

        val rn = Regex("(?i)(?:meu nome (?:e|eh|é) |me chamo |pode me chamar de |me chama de |sou o |sou a )([\\wÀ-ÿ]{2,20})")
        rn.find(msg)?.let { mm ->
            val n = mm.groupValues[1].trim().replaceFirstChar { it.uppercase() }
            prefs.edit().putString("n", n).apply()
        }

        var fatos = prefs.getString("f", "") ?: ""
        val pads = listOf(
            Regex("(?i)eu (?:adoro|amo|gosto de|curto) ([\\wÀ-ÿ ]{2,40}?)(?:,|\\.|e |$)", RegexOption.IGNORE_CASE) to "Gosta de %s",
            Regex("(?i)eu (?:odeio|detesto|nao gosto de) ([\\wÀ-ÿ ]{2,40}?)(?:,|\\.|e |$)", RegexOption.IGNORE_CASE) to "Nao gosta de %s",
            Regex("(?i)eu tenho (\\d{1,2}) anos") to "Tem %s anos"
        )
        pads.forEach { (rx, tpl) ->
            rx.findAll(msg).forEach { mm ->
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
