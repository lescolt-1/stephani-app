package org.kira.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class KiraAccessibilityService : AccessibilityService() {

    companion object {
        var instance: KiraAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { }
    override fun onInterrupt() { }

    fun tocarTela(x: Float, y: Float) {
        val path = Path()
        path.moveTo(x, y)
        val gesto = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        dispatchGesture(gesto, null, null)
    }

    fun lerTela(): String {
        val raiz = rootInActiveWindow ?: return ""
        val sb = StringBuilder()
        fun percorrer(node: AccessibilityNodeInfo?) {
            node ?: return
            node.text?.let { if (it.isNotBlank()) sb.append(it).append(" | ") }
            node.contentDescription?.let { if (it.isNotBlank()) sb.append(it).append(" | ") }
            for (i in 0 until node.childCount) percorrer(node.getChild(i))
        }
        percorrer(raiz)
        return sb.toString()
    }

    // ---- Encontrar no da arvore ----
    private fun acharPorViewId(id: String): AccessibilityNodeInfo? {
        val raiz = rootInActiveWindow ?: return null
        val lista = raiz.findAccessibilityNodeInfosByViewId(id)
        return if (lista.isNotEmpty()) lista[0] else null
    }

    private fun acharPorTexto(texto: String): AccessibilityNodeInfo? {
        val raiz = rootInActiveWindow ?: return null
        val lista = raiz.findAccessibilityNodeInfosByText(texto)
        return if (lista.isNotEmpty()) lista[0] else null
    }

    private fun clicarNo(node: AccessibilityNodeInfo?): Boolean {
        node ?: return false
        var atual: AccessibilityNodeInfo? = node
        while (atual != null) {
            if (atual.isClickable) {
                return atual.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            atual = atual.parent
        }
        // Fallback: toca no centro
        val rect = Rect()
        node.getBoundsInScreen(rect)
        tocarTela(rect.centerX().toFloat(), rect.centerY().toFloat())
        return true
    }

    private fun digitarNo(node: AccessibilityNodeInfo?, texto: String): Boolean {
        node ?: return false
        val args = android.os.Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, texto)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    // ---- API publica para WhatsApp ----
    fun enviarWhatsApp(contato: String, mensagem: String): String {
        try {
            // 1. Clica na lupa / busca do WhatsApp
            val busca = acharPorViewId("com.whatsapp:id/menuitem_search")
                ?: acharPorViewId("com.whatsapp:id/search")
            clicarNo(busca)
            Thread.sleep(1500)

            // 2. Digita o nome do contato
            val campo = acharPorViewId("com.whatsapp:id/search_input")
                ?: acharPorViewId("com.whatsapp:id/search_src_text")
                ?: acharPorTexto("Pesquisar")
            if (campo == null) return "nao achei a busca do WhatsApp"

            clicarNo(campo)
            Thread.sleep(500)
            digitarNo(campo, contato)
            Thread.sleep(2500)

            // 3. Clica no primeiro resultado (varre a arvore por clique com o nome)
            var raiz = rootInActiveWindow
            var achouContato: AccessibilityNodeInfo? = null
            fun procurar(node: AccessibilityNodeInfo?) {
                if (achouContato != null) return
                node ?: return
                val txt = node.text?.toString() ?: ""
                if (txt.contains(contato, ignoreCase = true) && node.isClickable) {
                    achouContato = node
                    return
                }
                for (i in 0 until node.childCount) procurar(node.getChild(i))
            }
            procurar(raiz)

            if (achouContato == null) return "contato '$contato' nao encontrado"
            clicarNo(achouContato)
            Thread.sleep(2500)

            // 4. Digita a mensagem
            val campoMsg = acharPorViewId("com.whatsapp:id/entry")
                ?: acharPorViewId("com.whatsapp:id/message_input")
            if (campoMsg == null) return "nao achei o campo de mensagem"

            clicarNo(campoMsg)
            Thread.sleep(500)
            digitarNo(campoMsg, mensagem)
            Thread.sleep(800)

            // 5. Clica em enviar
            val enviar = acharPorViewId("com.whatsapp:id/send")
            if (enviar != null) {
                clicarNo(enviar)
                return "mensagem enviada pra $contato"
            }

            // Fallback: aperta enter
            val args = android.os.Bundle()
            args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT, 1)
            return "mensagem escrita mas nao enviei"
        } catch (e: Exception) {
            return "erro WhatsApp: ${e.message}"
        }
    }
}
