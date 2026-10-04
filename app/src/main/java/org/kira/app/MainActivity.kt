package org.kira.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val p = getSharedPreferences("s", MODE_PRIVATE)
        val e = findViewById<EditText>(R.id.tokenInput)
        e.setText(p.getString("t", ""))

        // Carrega o modo salvo
        val modoAtual = p.getString("m", "eq") ?: "eq"
        when (modoAtual) {
            "sr" -> findViewById<RadioButton>(R.id.modoSerio).isChecked = true
            "dv" -> findViewById<RadioButton>(R.id.modoDivertido).isChecked = true
            else -> findViewById<RadioButton>(R.id.modoEquilibrado).isChecked = true
        }

        findViewById<Button>(R.id.salvarButton).setOnClickListener {
            val modo = when {
                findViewById<RadioButton>(R.id.modoSerio).isChecked -> "sr"
                findViewById<RadioButton>(R.id.modoDivertido).isChecked -> "dv"
                else -> "eq"
            }
            p.edit()
                .putString("t", e.text.toString().trim())
                .putString("m", modo)
                .apply()
            Toast.makeText(this, "Salvo!", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.acessibilidadeButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.iniciarButton).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                return@setOnClickListener
            }
            val s = Intent(this, KiraOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(s) else startService(s)
            moveToBack()
        }
    }

    private fun moveToBack() {
        moveTaskToBack(true)
    }
}
