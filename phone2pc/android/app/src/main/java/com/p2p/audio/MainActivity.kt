package com.p2p.audio

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Streams raw, unprocessed 48kHz / 16-bit PCM mono mic audio to a PC
 * over a plain TCP socket. No compression, no video, minimal buffering.
 *
 * Connect via:
 *  - USB:  adb reverse tcp:6000 tcp:6000   -> use IP 127.0.0.1
 *  - WiFi: use your PC's LAN IP directly
 */
class MainActivity : AppCompatActivity() {

    private val SAMPLE_RATE = 48000
    private val CHANNEL = AudioFormat.CHANNEL_IN_MONO
    private val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    private val RECORD_PERMISSION_CODE = 1001

    private var recordThread: Thread? = null
    private val streaming = AtomicBoolean(false)

    private lateinit var editIp: EditText
    private lateinit var editPort: EditText
    private lateinit var btnConnect: Button
    private lateinit var txtStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        editIp = findViewById(R.id.editIp)
        editPort = findViewById(R.id.editPort)
        btnConnect = findViewById(R.id.btnConnect)
        txtStatus = findViewById(R.id.txtStatus)

        btnConnect.setOnClickListener {
            if (!streaming.get()) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(
                        this, arrayOf(Manifest.permission.RECORD_AUDIO), RECORD_PERMISSION_CODE
                    )
                } else {
                    startStreaming()
                }
            } else {
                stopStreaming()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RECORD_PERMISSION_CODE &&
            grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startStreaming()
        } else {
            setStatus("Mic permission denied")
        }
    }

    private fun setStatus(s: String) {
        runOnUiThread { txtStatus.text = "Status: $s" }
    }

    @Suppress("DEPRECATION")
    private fun startStreaming() {
        val ip = editIp.text.toString().trim()
        val port = editPort.text.toString().trim().toIntOrNull() ?: 6000

        // Prefer UNPROCESSED (raw, no AGC/noise suppression/echo cancel) for max fidelity.
        val source = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            MediaRecorder.AudioSource.UNPROCESSED
        } else {
            MediaRecorder.AudioSource.MIC
        }

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) {
            setStatus("Device doesn't support this audio config")
            return
        }

        // Use a buffer a bit larger than the minimum to avoid underruns,
        // but keep it small for low latency. ~4x min buffer is a safe middle ground.
        val bufferSize = minBuf * 4

        var audioRecord: AudioRecord
        try {
            audioRecord = AudioRecord(source, SAMPLE_RATE, CHANNEL, ENCODING, bufferSize)
        } catch (e: Exception) {
            // fall back to MIC if UNPROCESSED isn't actually available on this device
            audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL, ENCODING, bufferSize)
        }

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            setStatus("AudioRecord init failed")
            return
        }

        streaming.set(true)
        setStatus("connecting to $ip:$port ...")

        recordThread = Thread {
            var socket: Socket? = null
            try {
                socket = Socket()
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(ip, port), 5000)
                val out = socket.getOutputStream()

                audioRecord.startRecording()
                setStatus("streaming (48kHz/16-bit PCM mono)")

                // 20ms chunks at 48kHz mono 16-bit = 960 samples = 1920 bytes
                val chunkFrames = 960
                val chunkBytes = ByteArray(chunkFrames * 2)

                while (streaming.get()) {
                    val read = audioRecord.read(chunkBytes, 0, chunkBytes.size)
                    if (read > 0) {
                        out.write(chunkBytes, 0, read)
                        out.flush()
                    }
                }
            } catch (e: Exception) {
                setStatus("error: ${e.message}")
            } finally {
                try { audioRecord.stop() } catch (_: Exception) {}
                audioRecord.release()
                try { socket?.close() } catch (_: Exception) {}
                streaming.set(false)
                setStatus("stopped")
            }
        }
        recordThread?.start()
        runOnUiThread { btnConnect.text = "Stop Streaming" }
    }

    private fun stopStreaming() {
        streaming.set(false)
        runOnUiThread { btnConnect.text = "Start Streaming" }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStreaming()
    }
}
