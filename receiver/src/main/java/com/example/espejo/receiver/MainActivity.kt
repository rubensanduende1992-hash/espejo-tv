package com.example.espejo.receiver

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {

    // true = llena toda la pantalla (puede recortar un poco)
    // false = mantiene proporcion (puede dejar barras negras)
    private val FILL = true

    private val SAMPLE_RATE = 44100

    private lateinit var root: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var infoText: TextView
    private var decoder: MediaCodec? = null
    private var audioTrack: AudioTrack? = null
    @Volatile private var running = false
    private var server: ServerSocket? = null

    private var nsd: NsdManager? = null
    private var regListener: NsdManager.RegistrationListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        root = FrameLayout(this)
        surfaceView = SurfaceView(this)
        root.addView(
            surfaceView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        )
        infoText = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 28f
            setPadding(48, 48, 48, 48)
            text = "Esperando al celular..."
        }
        root.addView(
            infoText,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            )
        )
        setContentView(root)
        surfaceView.holder.addCallback(this)
        volumeControlStream = AudioManager.STREAM_MUSIC
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        running = true
        thread { serverLoop(holder) }
    }

    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        running = false
        unregisterNsd()
        try { server?.close() } catch (_: Exception) {}
    }

    // ---------- Descubrimiento automatico (NSD) ----------

    private fun registerNsd() {
        if (regListener != null) return
        val info = NsdServiceInfo().apply {
            serviceName = "EspejoTV"
            serviceType = "_espejotv._tcp."
            port = 5000
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {}
            override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) {}
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
        }
        regListener = l
        nsd = getSystemService(NsdManager::class.java)
        try {
            nsd?.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (_: Exception) {
            regListener = null
        }
    }

    private fun unregisterNsd() {
        try { regListener?.let { nsd?.unregisterService(it) } } catch (_: Exception) {}
        regListener = null
    }

    private fun localIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
    } catch (_: Exception) {
        null
    }

    private fun showWaiting() {
        val ip = localIp()
        runOnUiThread {
            infoText.text = if (ip != null)
                "Esperando al celular...\nIP de esta TV: $ip"
            else
                "Esperando al celular..."
            infoText.visibility = View.VISIBLE
        }
    }

    // ---------- Servidor ----------

    private fun serverLoop(holder: SurfaceHolder) {
        try {
            server = ServerSocket(5000)
            registerNsd()
            while (running) {
                showWaiting()
                val socket = server!!.accept()
                socket.tcpNoDelay = true
                handleClient(socket, holder)
            }
        } catch (_: Exception) {
        }
    }

    private fun startAudioTrack() {
        releaseAudioTrack()
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        val builder = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
        if (Build.VERSION.SDK_INT >= 26) {
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }
        val track = builder.build()
        track.play()
        audioTrack = track
    }

    private fun releaseAudioTrack() {
        try { audioTrack?.stop(); audioTrack?.release() } catch (_: Exception) {}
        audioTrack = null
    }

    private fun handleClient(socket: java.net.Socket, holder: SurfaceHolder) {
        try {
            val input = DataInputStream(socket.getInputStream().buffered())
            val output = DataOutputStream(socket.getOutputStream())

            // Le decimos al celular la resolucion de la TV
            val dm = resources.displayMetrics
            output.writeInt(dm.widthPixels)
            output.writeInt(dm.heightPixels)
            output.flush()

            startAudioTrack()

            while (running) {
                when (input.readByte().toInt()) {
                    0 -> {
                        val w = input.readInt()
                        val h = input.readInt()
                        startDecoder(w, h, holder)
                        adjustView(w, h)
                        runOnUiThread { infoText.visibility = View.GONE }
                    }
                    1 -> {
                        val flags = input.readInt()
                        val pts = input.readLong()
                        val size = input.readInt()
                        val data = ByteArray(size)
                        input.readFully(data)
                        feed(data, pts, flags)
                    }
                    2 -> {
                        val size = input.readInt()
                        val data = ByteArray(size)
                        input.readFully(data)
                        // No bloqueante: si el buffer esta lleno, descarta
                        audioTrack?.write(data, 0, size, AudioTrack.WRITE_NON_BLOCKING)
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            try { decoder?.stop(); decoder?.release() } catch (_: Exception) {}
            decoder = null
            releaseAudioTrack()
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun startDecoder(w: Int, h: Int, holder: SurfaceHolder) {
        try { decoder?.stop(); decoder?.release() } catch (_: Exception) {}
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
        if (Build.VERSION.SDK_INT >= 30) {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        val dec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        dec.configure(format, holder.surface, null, 0)
        dec.start()
        decoder = dec
    }

    private fun feed(data: ByteArray, pts: Long, flags: Int) {
        val dec = decoder ?: return
        val info = MediaCodec.BufferInfo()
        var idx: Int
        while (true) {
            drain(dec, info)
            idx = dec.dequeueInputBuffer(5000)
            if (idx >= 0) break
            if (!running) return
        }
        val buf = dec.getInputBuffer(idx)!!
        buf.clear()
        buf.put(data)
        dec.queueInputBuffer(idx, 0, data.size, pts, flags)
        drain(dec, info)
    }

    private fun drain(dec: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val out = dec.dequeueOutputBuffer(info, 0)
            if (out >= 0) dec.releaseOutputBuffer(out, true) else break
        }
    }

    // Ajusta el tamano del SurfaceView para respetar la proporcion del video
    private fun adjustView(w: Int, h: Int) {
        runOnUiThread {
            val sw = root.width.toFloat()
            val sh = root.height.toFloat()
            if (sw == 0f || sh == 0f) return@runOnUiThread
            // Horizontal: llena la pantalla. Vertical: se ajusta (barras negras a los lados)
            val fill = FILL && w >= h
            val scale = if (fill) maxOf(sw / w, sh / h) else minOf(sw / w, sh / h)
            val lp = surfaceView.layoutParams as FrameLayout.LayoutParams
            lp.width = (w * scale).toInt()
            lp.height = (h * scale).toInt()
            lp.gravity = Gravity.CENTER
            surfaceView.layoutParams = lp
        }
    }
}
