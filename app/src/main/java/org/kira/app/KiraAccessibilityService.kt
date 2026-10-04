package org.kira.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

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
        fun percorrer(node: android.view.accessibility.AccessibilityNodeInfo?) {
            node ?: return
            node.text?.let { if (it.isNotBlank()) sb.append(it).append(" | ") }
            for (i in 0 until node.childCount) {
                percorrer(node.getChild(i))
            }
        }
        percorrer(raiz)
        return sb.toString()
    }
}
