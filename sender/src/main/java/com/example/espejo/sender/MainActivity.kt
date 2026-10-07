package com.example.espejo.sender

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var ipField: EditText

    private val capture = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val ip = ipField.text.toString().trim()
            getSharedPreferences("cfg", Context.MODE_PRIVATE).edit().putString("ip", ip).apply()
            val i = Intent(this, MirrorService::class.java).apply {
                putExtra("code", result.resultCode)
                putExtra("data", result.data)
                putExtra("host", ip)
            }
            startForegroundService(i)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions(
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS),
            1
        )

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        layout.addView(TextView(this).apply { text = "IP de la TV (la muestra la app receptora)" })

        ipField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_PHONE
            hint = "192.168.1.50"
            setText(getSharedPreferences("cfg", Context.MODE_PRIVATE).getString("ip", ""))
        }
        layout.addView(ipField)

        layout.addView(Button(this).apply {
            text = "Iniciar espejo"
            setOnClickListener {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    Toast.makeText(
                        this@MainActivity,
                        "Acepta el permiso de audio para transmitir el sonido",
                        Toast.LENGTH_LONG
                    ).show()
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
                    return@setOnClickListener
                }
                val mpm = getSystemService(MediaProjectionManager::class.java)
                capture.launch(mpm.createScreenCaptureIntent())
            }
        })
        layout.addView(Button(this).apply {
            text = "Detener"
            setOnClickListener { stopService(Intent(this@MainActivity, MirrorService::class.java)) }
        })
        setContentView(layout)
    }
}
