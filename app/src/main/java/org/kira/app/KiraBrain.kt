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
    private var ultimaPerguntouNome = false

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
            "Voce AINDA NAO sabe o nome do usuario. Pergunte na PRIMEIRA resposta e lembre pra sempre."
        else "Voce sabe que o nome dele e $nome. NUNCA pergunte o nome de novo."

        val estilo = when (modo) {
            "sr" -> "MODO SERIO: adulta, direta, clara. Sarcasmo leve. Sem girias."
            "dv" -> "MODO DIVERTIDO: adolescente zoeira, girias femininas."
            else -> "MODO EQUILIBRADO: personalidade forte mas natural."
        }

        return "Voce e a Stephani Bittencourt. Fala SEMPRE em portugues do Brasil.\n\n" +
        "$relacao\n\n$estilo\n\nFatos que voce sabe sobre ele:\n$fatos\n\n" +
        "═══════════════════════════════\n" +
        "VOCE EXECUTA ACOES NO CELULAR!\n" +
        "═══════════════════════════════\n" +
        "Quando o usuario pedir algo, sua resposta DEVE incluir o comando.\n\n" +
        "Para ABRIR APP: [CMD] abrir <nome>\n" +
        "Para MANDAR WHATSAPP: [CMD] whatsapp <contato> | <mensagem>\n" +
        "Para BATERIA: [CMD] bateria\n" +
        "Para HORARIO: [CMD] horario\n" +
        "Para LER TELA: [CMD] ler_tela\n\n" +
        "EXEMPLOS:\n" +
        "Usuario: 'abre o WhatsApp'\n" +
        "Voce: 'Ja to abrindo! [CMD] abrir whatsapp'\n\n" +
        "Usuario: 'abre o Instagram'\n" +
        "Voce: 'Partiu Insta! [CMD] abrir instagram'\n\n" +
        "Usuario: 'manda bom dia abencoado pra Dalva'\n" +
        "Voce: 'Mandei um carinho especial! [CMD] whatsapp Dalva | Bom dia, Dalva! Que seu dia seja repleto de luz e paz.'\n\n" +
        "Usuario: 'manda bom dia fabuloso pro Joao'\n" +
        "Voce: 'Criei uma mensagem unica! [CMD] whatsapp Joao | Bom dia, Joao! Que hoje seja um dia de conquistas gigantescas!'\n\n" +
        "REGRAS CRITICAS:\n" +
        "1. SEMPRE emita o [CMD] quando for executar algo.\n" +
        "2. O [CMD] deve ficar SOZINHO na ultima linha.\n" +
        "3. Invente mensagens UNICAS. Nunca repita a mesma.\n" +
        "4. NUNCA escreva pensamento em ingles.\n" +
        "5. Respostas CURTAS (1-2 frases + o comando)."
    }

    suspend fun responder(context: Context, mensagem: String): String = withContext(Dispatchers.IO) {
        val p = context.getSharedPreferences("s", Context.MODE_PRIVATE)
        val t = p.getString("t", "") ?: ""
        if (t.isEmpty()) return@withContext "Coloca a credencial nas configuracoes."

        extrairFato(context, mensagem)

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

    private suspend fun processarResposta(context: Context, textoBruto: String): String {
        val comandos = extrairComandos(textoBruto)
        var t = limpar(textoBruto)

        ultimaPerguntouNome = textoBruto.contains("nome", ignoreCase = true) ||
            textoBruto.contains("como se chama", ignoreCase = true) ||
            textoBruto.contains("qual seu nome", ignoreCase = true)

        for (cmd in comandos) {
            val resultado = executar(context, cmd)
            if (resultado != "comando desconhecido" && resultado != "?" && !resultado.startsWith("erro:")) {
                t = if (t.isBlank()) "[Resultado: $resultado]" else "$t\n[Resultado: $resultado]"
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
        t = t.replace(Regex("\\[CMD\\].*"), "")

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
        if (melhorPacote == null) return "nao achei o app '$nome'"
        val intent = pm.getLaunchIntentForPackage(melhorPacote)
        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            "abri o $melhorNome"
        } catch (e: Exception) { "erro ao abrir: ${e.message}" }
    }

    private fun executar(context: Context, cmd: String): String {
        val c = cmd.trim()
        val cLow = c.lowercase()

        return try {
            // ---- WHATSAPP ----
            val isWhatsApp = cLow.contains("whatsapp") || cLow.contains("whats") || cLow.contains("wpp")
            val isMensagem = cLow.contains("mensagem") || cLow.contains("manda") ||
                cLow.contains("mandar") || cLow.contains("envia") || cLow.contains("enviar")
            val temPipe = c.contains("|")

            if (isWhatsApp && (temPipe || isMensagem)) {
                var contato = ""
                var mensagem = ""
                if (temPipe) {
                    val partes = c.split("|")
                    contato = partes[0].replace(Regex("(?i).*whatsapp|.*whats|.*wpp|.*mandar|.*manda|.*enviar|.*envia|.*mensagem"), "").trim()
                    mensagem = partes[1].trim()
                } else {
                    val rx = Regex("(?i)(?:para|pro|pra)\\s+([A-Z][\\wÀ-ÿ]+)")
                    rx.find(c)?.let { contato = it.groupValues[1].trim() }
                    val rx2 = Regex("(?i)dizendo\\s+(.+)")
                    rx2.find(c)?.let { mensagem = it.groupValues[1].trim() }
                    if (mensagem.isEmpty()) {
                        mensagem = c.replace(Regex("(?i).*(manda|mandar|envia|enviar|mensagem|whatsapp|whats|wpp|para|pro|pra)\\s*"), "").trim()
                    }
                }
                if (contato.isEmpty()) return "nao entendi o contato"
                if (mensagem.isEmpty()) mensagem = "Oi! Tudo bem?"

                abrirAppPorNome(context, "whatsapp")
                Thread.sleep(2500)
                return KiraAccessibilityService.instance?.enviarWhatsApp(contato, mensagem)
                    ?: "acessibilidade desligada"
            }
            // ---- ABRIR APP ----
            if (cLow.contains("abrir") || cLow.contains("abre") || cLow.contains("abri")) {
                val rx = Regex("(?i)(?:abrir|abre|abri)\\s+(?:o\\s+|a\\s+)?([\\wÀ-ÿ ]+?)(?:\\s*$|\\s+e\\s+)")
                val m = rx.find(c)
                if (m != null) {
                    val nome = m.groupValues[1].trim()
                    if (nome.isNotEmpty() && nome.length > 1) {
                        return abrirAppPorNome(context, nome)
                    }
                }
                return "nao entendi qual app"
            }
            // ---- TOCAR ----
            if (cLow.startsWith("tocar ")) {
                val p = c.substring(6).trim().split(" ")
                if (p.size >= 2) {
                    KiraAccessibilityService.instance?.tocarTela(p[0].toFloatOrNull() ?: 0f, p[1].toFloatOrNull() ?: 0f)
                    return "toquei"
                }
                return "errado"
            }
            // ---- LER TELA ----
            if (cLow.startsWith("ler_tela") || cLow.contains("ler a tela") || cLow.contains("le a tela")) {
                val txt = KiraAccessibilityService.instance?.lerTela() ?: "off"
                return if (txt.length > 300) txt.substring(0, 300) + "..." else txt
            }
            // ---- PESQUISAR ----
            if (cLow.contains("pesquisar ")) {
                return busca(c.substringAfter("pesquisar").trim())
            }
            // ---- BATERIA ----
            if (cLow.contains("bateria")) {
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
                return "${bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)}%"
            }
            // ---- HORARIO ----
            if (cLow.contains("horario") || cLow.contains("que horas")) {
                return java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date())
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
            if (abs.isNotEmpty()) abs else "nada"
        } catch (e: Exception) { "erro" }
    }

    fun extrairFato(context: Context, mensagem: String) {
        val prefs = context.getSharedPreferences("s", Context.MODE_PRIVATE)
        val nomeAtual = prefs.getString("n", "") ?: ""
        val msg = mensagem.trim()

        if (nomeAtual.isEmpty() && ultimaPerguntouNome) {
            val palavras = msg.split(" ").filter { it.isNotBlank() }
            if (palavras.size <= 3 && msg.length <= 30) {
                val limpo = msg
                    .replace(Regex("(?i)^(meu nome (é|e|eh) )"), "")
                    .replace(Regex("(?i)^(me chamo |me chama )"), "")
                    .replace(Regex("(?i)^(pode me chamar de |me chama de )"), "")
                    .replace(Regex("(?i)^(sou o |sou a |aqui é o |aqui e o )"), "")
                    .trim()
                if (limpo.isNotEmpty() && limpo.length in 2..25) {
                    prefs.edit().putString("n", limpo.replaceFirstChar { it.uppercase() }).apply()
                    ultimaPerguntouNome = false
                }
            }
        }

        val rn = Regex("(?i)(?:meu nome (?:é|e|eh) |me chamo |pode me chamar de |me chama de |sou o |sou a |aqui é o |aqui e o )([\\wÀ-ÿ]+)")
        rn.find(msg)?.let { mm ->
            val n = mm.groupValues[1].trim().replaceFirstChar { it.uppercase() }
            if (n.length in 2..25) prefs.edit().putString("n", n).apply()
        }

        var fatos = prefs.getString("f", "") ?: ""
        val pads = listOf(
            Regex("(?i)eu (?:adoro|amo|gosto de|curto) ([\\wÀ-ÿ ]+?)(?:,|\\.|e |$)", RegexOption.IGNORE_CASE) to "Gosta de %s",
            Regex("(?i)eu (?:odeio|detesto|nao gosto de) ([\\wÀ-ÿ ]+?)(?:,|\\.|e |$)", RegexOption.IGNORE_CASE) to "Nao gosta de %s",
            Regex("(?i)eu tenho ([\\d]+) anos") to "Tem %s anos",
            Regex("(?i)eu trabalho (?:com|em|de) ([\\wÀ-ÿ ]+?)(?:,|\\.|e |$)", RegexOption.IGNORE_CASE) to "Trabalha com %s",
            Regex("(?i)eu moro (?:em|no|na) ([\\wÀ-ÿ ]+?)(?:,|\\.|e |$)", RegexOption.IGNORE_CASE) to "Mora em %s"
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
