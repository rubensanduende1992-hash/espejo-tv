package com.example.espejo.sender

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.net.Inet4Address

class MainActivity : AppCompatActivity() {

    private lateinit var ipField: EditText
    private lateinit var status: TextView
    private var nsd: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

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

        status = TextView(this).apply { text = "Buscando la TV en el WiFi..." }
        layout.addView(status)

        layout.addView(TextView(this).apply {
            text = "IP de la TV (se completa sola; si no, escribila):"
            setPadding(0, 32, 0, 0)
        })

        ipField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_PHONE
            hint = "192.168.1.50"
            setText(getSharedPreferences("cfg", Context.MODE_PRIVATE).getString("ip", ""))
        }
        layout.addView(ipField)

        layout.addView(Button(this).apply {
            text = "Iniciar espejo"
            setOnClickListener {
                if (ipField.text.toString().trim().isEmpty()) {
                    Toast.makeText(
                        this@MainActivity,
                        "No se encontro la TV. Abri la app de la TV o escribi su IP.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }
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

        startDiscovery()
    }

    // Busca la TV en la red local (la app de la TV se anuncia sola)
    @Suppress("DEPRECATION")
    private fun startDiscovery() {
        val mgr = getSystemService(NsdManager::class.java)
        nsd = mgr
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                runOnUiThread { status.text = "No se pudo buscar la TV. Escribi la IP a mano." }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(service: NsdServiceInfo) {}

            override fun onServiceFound(service: NsdServiceInfo) {
                if (!service.serviceType.contains("_espejotv")) return
                mgr.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host = info.host
                        if (host is Inet4Address) {
                            val ip = host.hostAddress ?: return
                            runOnUiThread {
                                ipField.setText(ip)
                                status.text = "TV encontrada: $ip"
                            }
                        }
                    }
                })
            }
        }
        discoveryListener = listener
        try {
            mgr.discoverServices("_espejotv._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Exception) {
            status.text = "No se pudo buscar la TV. Escribi la IP a mano."
        }
    }

    override fun onDestroy() {
        try { discoveryListener?.let { nsd?.stopServiceDiscovery(it) } } catch (_: Exception) {}
        super.onDestroy()
    }
}
