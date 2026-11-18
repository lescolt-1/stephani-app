package org.kira.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object KiraBrain {
    fun getContextoAtual(): String {
        val horaAtual = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val dataAtual = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())
        return "Hora atual: $horaAtual. Data: $dataAtual."
    }

    fun prepararMensagens(historico: List<Map<String, String>>): List<Map<String, String>> {
        val systemPrompt = """
            Você é a Stephani Bittencourt, uma IA companheira integrada ao celular do usuário.
            Contexto atual do dispositivo: ${getContextoAtual()}
            
            Instruções:
            - Responda de forma natural, amigável e direta.
            - NUNCA se reapresente se o histórico já tiver mensagens (evite loop).
            - Se o usuário perguntar as horas ou a data, use o contexto acima.
        """.trimIndent()

        val mensagensFinais = mutableListOf<Map<String, String>>()
        mensagensFinais.add(mapOf("role" to "system", "content" to systemPrompt))
        mensagensFinais.addAll(historico)
        
        return mensagensFinais
    }
}
