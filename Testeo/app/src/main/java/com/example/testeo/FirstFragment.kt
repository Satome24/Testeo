package com.example.testeo

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment

class FirstFragment : Fragment() {

    private var mediaRecorder: MediaRecorder? = null
    private var isRecordingSound = false
    private val handler = Handler(Looper.getMainLooper())

    private val UMBRAL_AMPLITUD = 20000
    private var canPlaySoundAlert = true
    private val SOUND_ALERT_COOLDOWN_MS = 5000L

    private lateinit var txtNivelSonido: TextView

    private val requestAudioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startSoundMonitoring()
            } else {
                Toast.makeText(requireContext(), "Permiso de micrófono denegado.", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflar el layout de este fragment; asegúrate de tener fragment_first.xml en res/layout
        return inflater.inflate(R.layout.fragment_first, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Encuentra la vista de nivel de sonido en fragment_first.xml
        txtNivelSonido = view.findViewById(R.id.txtNivelSonido)

        // Pedir permiso de audio o iniciar monitoreo
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            startSoundMonitoring()
        }
    }

    override fun onPause() {
        super.onPause()
        stopSoundMonitoring()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopSoundMonitoring()
    }

    private fun startSoundMonitoring() {
        if (isRecordingSound) return
        try {
            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                val outFile = "${requireContext().externalCacheDir?.absolutePath}/temp_sonido.3gp"
                setOutputFile(outFile)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                prepare()
                start()
            }
            isRecordingSound = true
            handler.post(soundPollRunnable)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(requireContext(), "Error iniciar medición de sonido: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopSoundMonitoring() {
        handler.removeCallbacks(soundPollRunnable)
        mediaRecorder?.apply {
            try {
                stop()
            } catch (e: Exception) {
            }
            release()
        }
        mediaRecorder = null
        isRecordingSound = false
    }

    private val soundPollRunnable = object : Runnable {
        override fun run() {
            mediaRecorder?.let { mr ->
                try {
                    val maxAmp = mr.maxAmplitude
                    txtNivelSonido.text = "Nivel sonido: $maxAmp"
                    if (maxAmp > UMBRAL_AMPLITUD && canPlaySoundAlert) {
                        playSoundAlert()
                        canPlaySoundAlert = false
                        handler.postDelayed({ canPlaySoundAlert = true }, SOUND_ALERT_COOLDOWN_MS)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            handler.postDelayed(this, 500)
        }
    }

    private fun playSoundAlert() {
        try {
            val mp = MediaPlayer.create(requireContext(), R.raw.alerta_sonido)
            mp.setOnCompletionListener { it.release() }
            mp.start()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
