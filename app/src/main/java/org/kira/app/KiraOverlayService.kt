package org.kira.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

class KiraOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var avatarView: View
    private lateinit var chatView: View
    private var chatAberto = false

    private lateinit var chatLayout: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var reconhecedor: SpeechRecognizer

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        criarNotificacao()
        KiraBrain.inicializarVoz(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        criarAvatar()
    }

    private fun criarNotificacao() {
        val canalId = "kira_canal"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canal = NotificationChannel(canalId, "Kira Ativa", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
        }

        val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, canalId) else Notification.Builder(this)

        startForeground(1, notif
            .setContentTitle("Stephani esta ativa")
            .setContentText("Toque no avatar para conversar")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build())
    }

    private fun criarAvatar() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 100
        params.y = 400

        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL

        val img = ImageView(this)
        img.setImageResource(R.drawable.kira_feliz)
        val tam = (100 * resources.displayMetrics.density).toInt()
        img.layoutParams = LinearLayout.LayoutParams(tam, tam)
        img.scaleType = ImageView.ScaleType.FIT_CENTER
        container.addView(img)

        var xIni = 0; var yIni = 0; var tx = 0f; var ty = 0f; var movendo = false

        img.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    xIni = params.x; yIni = params.y
                    tx = event.rawX; ty = event.rawY; movendo = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - tx; val dy = event.rawY - ty
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) movendo = true
                    params.x = xIni + dx.toInt(); params.y = yIni + dy.toInt()
                    windowManager.updateViewLayout(avatarView, params); true
                }
                MotionEvent.ACTION_UP -> {
                    if (!movendo) toggleChat(); true
                }
                else -> false
            }
        }

        avatarView = container
        windowManager.addView(avatarView, params)
    }

    private fun toggleChat() {
        if (chatAberto) {
            if (::chatView.isInitialized) windowManager.removeView(chatView)
            chatAberto = false
        } else {
            criarChat()
            chatAberto = true
        }
    }

    private fun criarChat() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            (520 * resources.displayMetrics.density).toInt(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.BOTTOM

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(0xEE1A1A1A.toInt())
        root.setPadding(20, 20, 20, 20)

        val titulo = TextView(this)
        titulo.text = "Stephani"
        titulo.setTextColor(0xFF00E5B0.toInt())
        titulo.textSize = 20f
        root.addView(titulo)

        chatScroll = ScrollView(this)
        chatLayout = LinearLayout(this)
        chatLayout.orientation = LinearLayout.VERTICAL
        chatScroll.addView(chatLayout)
        root.addView(chatScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val inputLayout = LinearLayout(this)
        inputLayout.orientation = LinearLayout.HORIZONTAL

        val input = EditText(this)
        input.hint = "Digite..."
        input.setTextColor(0xFFFFFFFF.toInt())
        input.setHintTextColor(0xFF888888.toInt())
        inputLayout.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val btnMic = Button(this)
        btnMic.text = "🎤"
        inputLayout.addView(btnMic)

        val btnEnviar = Button(this)
        btnEnviar.text = "Enviar"
        inputLayout.addView(btnEnviar)

        root.addView(inputLayout)

        adicionarMensagem("Stephani", "Oi! To aqui. Fala ou digita.")

        btnEnviar.setOnClickListener {
            val msg = input.text.toString().trim()
            if (msg.isEmpty()) return@setOnClickListener
            input.setText("")
            enviarMensagem(msg)
        }

        btnMic.setOnClickListener { ouvirVoz(input) }

        chatView = root
        windowManager.addView(chatView, params)
    }

    private fun enviarMensagem(msg: String) {
        adicionarMensagem("Voce", msg)
        KiraBrain.extrairFato(this, msg)
        CoroutineScope(Dispatchers.Main).launch {
            val resposta = KiraBrain.responder(this@KiraOverlayService, msg)
            adicionarMensagem("Stephani", resposta)
            KiraBrain.falar(resposta)
        }
    }

    private fun ouvirVoz(input: EditText) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            adicionarMensagem("Stephani", "Reconhecimento de voz nao disponivel nesse celular.")
            return
        }

        KiraBrain.pararFala()

        if (!::reconhecedor.isInitialized) {
            reconhecedor = SpeechRecognizer.createSpeechRecognizer(this)
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Fala ai, pai...")
        }

        reconhecedor.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(e: Int) {
                adicionarMensagem("Stephani", "Nao entendi, tenta de novo.")
            }
            override fun onResults(results: Bundle?) {
                val falado = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                if (falado.isNotEmpty()) {
                    input.setText("")
                    enviarMensagem(falado)
                }
            }
            override fun onPartialResults(p: Bundle?) {}
            override fun onEvent(t: Int, p: Bundle?) {}
        })

        reconhecedor.startListening(intent)
    }

    private fun adicionarMensagem(autor: String, texto: String) {
        val tv = TextView(this)
        tv.text = "$autor: $texto"
        tv.setTextColor(0xFFFFFFFF.toInt())
        tv.textSize = 14f
        tv.setPadding(0, 8, 0, 8)
        chatLayout.addView(tv)
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        super.onDestroy()
        KiraBrain.pararFala()
        if (::avatarView.isInitialized) try { windowManager.removeView(avatarView) } catch (_: Exception) {}
        if (::chatView.isInitialized) try { windowManager.removeView(chatView) } catch (_: Exception) {}
    }
}
