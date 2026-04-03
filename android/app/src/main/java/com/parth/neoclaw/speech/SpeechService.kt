package com.parth.neoclaw.speech

import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.google.gson.Gson
import com.parth.neoclaw.storage.SecureStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException

class SpeechService(private val context: Context, private val secureStorage: SecureStorage) {

    private val client = OkHttpClient()
    private val gson = Gson()
    private var speechRecognizer: SpeechRecognizer? = null
    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null

    private var currentOnTranscription: ((String) -> Unit)? = null
    private var currentOnError: ((String) -> Unit)? = null
    private var currentMode: String = "native"

    fun setMode(mode: String) {
        currentMode = mode
    }

    fun startListening(onTranscription: (String) -> Unit, onError: (String) -> Unit) {
        currentOnTranscription = onTranscription
        currentOnError = onError

        if (currentMode == "whisper") {
            startWhisperRecording()
        } else {
            Handler(Looper.getMainLooper()).post {
                startNativeListening()
            }
        }
    }

    fun stopListening() {
        if (currentMode == "whisper") {
            stopWhisperRecording()
        } else {
            Handler(Looper.getMainLooper()).post {
                speechRecognizer?.stopListening()
            }
        }
    }

    private fun startNativeListening() {
        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                currentOnError?.invoke("Speech recognition error code: $error")
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    currentOnTranscription?.invoke(matches[0])
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    // Update partially if needed, but we'll focus on full results for now
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        speechRecognizer?.startListening(intent)
    }

    private fun startWhisperRecording() {
        try {
            audioFile = File(context.cacheDir, "speech_record_${System.currentTimeMillis()}.m4a")
            mediaRecorder = MediaRecorder(context).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(audioFile?.absolutePath)
                prepare()
                start()
            }
        } catch (e: Exception) {
            currentOnError?.invoke("Failed to start recording: ${e.message}")
        }
    }

    private fun stopWhisperRecording() {
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
            mediaRecorder = null
            
            val file = audioFile ?: return
            transcribeWithWhisper(file)
        } catch (e: Exception) {
            currentOnError?.invoke("Failed to stop recording: ${e.message}")
        }
    }

    private fun transcribeWithWhisper(file: File) {
        val apiKey = secureStorage.read(SecureStorage.Key.OPENAI_API_KEY)
        if (apiKey.isNullOrEmpty()) {
            currentOnError?.invoke("OpenAI API Key is missing. Please set it in Settings.")
            file.delete()
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", file.name, file.asRequestBody("audio/mp4".toMediaTypeOrNull()))
                    .addFormDataPart("model", "whisper-1")
                    .build()

                val request = Request.Builder()
                    .url("https://api.openai.com/v1/audio/transcriptions")
                    .post(requestBody)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val body = response.body?.string()
                        withContext(Dispatchers.Main) {
                            currentOnError?.invoke("Whisper API error: ${response.code} $body")
                        }
                    } else {
                        val bodyStr = response.body?.string()
                        val result = bodyStr?.let {
                            val map = gson.fromJson(it, Map::class.java)
                            map["text"] as? String
                        }
                        withContext(Dispatchers.Main) {
                            if (!result.isNullOrEmpty()) {
                                currentOnTranscription?.invoke(result)
                            } else {
                                currentOnError?.invoke("Empty transcription result")
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                withContext(Dispatchers.Main) {
                    currentOnError?.invoke("Network error connecting to Whisper: ${e.message}")
                }
            } finally {
                file.delete()
            }
        }
    }
}
