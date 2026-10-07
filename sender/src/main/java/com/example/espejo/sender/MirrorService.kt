package com.example.espejo.sender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.view.Display
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket

class MirrorService : Service() {

    private var projection: MediaProjection? = null
    private var encoder: MediaCodec? = null
    private var display: VirtualDisplay? = null
    private var socket: Socket? = null
    private var out: DataOutputStream? = null
    private var audioRecord: AudioRecord? = null
    private val lock = Any() // protege las escrituras al socket (video + audio)

    @Volatile private var running = false
    @Volatile private var restart = false
    private var tvW = 1920
    private var tvH = 1080

    private val SAMPLE_RATE = 44100

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || running) return START_NOT_STICKY

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("mirror", "Espejo", NotificationManager.IMPORTANCE_LOW)
        )
        val notif = Notification.Builder(this, "mirror")
            .setContentTitle("Transmitiendo pantalla a la TV")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .build()
        startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val code = intent.getIntExtra("code", 0)
        val data = intent.getParcelableExtra<Intent>("data") ?: return START_NOT_STICKY
        val host = intent.getStringExtra("host") ?: return START_NOT_STICKY

        running = true
        Thread { stream(code, data, host) }.start()
        return START_NOT_STICKY
    }

    private fun stream(code: Int, data: Intent, host: String) {
        try {
            socket = Socket(host, 5000).also { it.tcpNoDelay = true }
            out = DataOutputStream(socket!!.getOutputStream().buffered(256 * 1024))
            val input = DataInputStream(socket!!.getInputStream())
            tvW = input.readInt()
            tvH = input.readInt()

            val mpm = getSystemService(MediaProjectionManager::class.java)
            projection = mpm.getMediaProjection(code, data)
            projection!!.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { running = false }
            }, null)

            startEncoder()
            startAudio()

            val info = MediaCodec.BufferInfo()
            while (running) {
                if (restart) {
                    restart = false
                    releaseEncoder()
                    startEncoder()
                }
                val enc = encoder ?: break
                val idx = enc.dequeueOutputBuffer(info, 100_000)
                if (idx >= 0) {
                    val buf = enc.getOutputBuffer(idx)!!
                    val bytes = ByteArray(info.size)
                    buf.position(info.offset)
                    buf.get(bytes)
                    synchronized(lock) {
                        out!!.writeByte(1)
                        out!!.writeInt(info.flags)
                        out!!.writeLong(info.presentationTimeUs)
                        out!!.writeInt(bytes.size)
                        out!!.write(bytes)
                        out!!.flush()
                    }
                    enc.releaseOutputBuffer(idx, false)
                }
            }
        } catch (_: Exception) {
        } finally {
            cleanup()
            stopSelf()
        }
    }

    // ---------- AUDIO ----------

    private fun startAudio() {
        try {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()

            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
            )

            val rec = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuf * 2)
                .setAudioPlaybackCaptureConfig(config)
                .build()
            audioRecord = rec
            rec.startRecording()

            Thread {
                val chunk = ByteArray(4096)
                try {
                    while (running) {
                        val n = rec.read(chunk, 0, chunk.size)
                        if (n > 0) {
                            synchronized(lock) {
                                out!!.writeByte(2)
                                out!!.writeInt(n)
                                out!!.write(chunk, 0, n)
                                out!!.flush()
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }.start()
        } catch (e: Exception) {
            // Si falla el audio (permiso denegado, etc.) el video sigue funcionando
            e.printStackTrace()
        }
    }

    // ---------- VIDEO ----------

    @Suppress("DEPRECATION")
    private fun phoneSize(): Point {
        val dm = getSystemService(DisplayManager::class.java)
        val p = Point()
        dm.getDisplay(Display.DEFAULT_DISPLAY).getRealSize(p)
        return p
    }

    private fun startEncoder() {
        val p = phoneSize()
        val scale = minOf(tvW.toFloat() / p.x, tvH.toFloat() / p.y, 1f)
        val w = ((p.x * scale).toInt() / 16) * 16
        val h = ((p.y * scale).toInt() / 16) * 16

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, w * h * 4)
            setInteger(MediaFormat.KEY_FRAME_RATE, 60)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000)
            setInteger(MediaFormat.KEY_LATENCY, 1)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = enc.createInputSurface()
        enc.start()
        encoder = enc

        display = projection!!.createVirtualDisplay(
            "espejo", w, h, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface, null, null
        )

        synchronized(lock) {
            out!!.writeByte(0)
            out!!.writeInt(w)
            out!!.writeInt(h)
            out!!.flush()
        }
    }

    private fun releaseEncoder() {
        try { display?.release() } catch (_: Exception) {}
        try { encoder?.stop(); encoder?.release() } catch (_: Exception) {}
        display = null
        encoder = null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (running) restart = true
    }

    private fun cleanup() {
        running = false
        releaseEncoder()
        try { audioRecord?.stop(); audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try { projection?.stop() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }
}
