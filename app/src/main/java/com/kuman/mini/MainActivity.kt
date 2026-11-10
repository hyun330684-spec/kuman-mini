package com.kuman.mini

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.graphics.Bitmap
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.gguf.GgufMetadata
import com.arm.aichat.gguf.GgufMetadataReader
import com.kuman.sd.SdBridge
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

class MainActivity : AppCompatActivity() {

    // Android views
    private lateinit var faceView: FaceView
    private lateinit var statusTv: TextView
    private lateinit var ggufTv: TextView
    private lateinit var metaScroll: View
    private lateinit var messagesRv: RecyclerView
    private lateinit var inputRow: View
    private lateinit var userInputEt: EditText
    private lateinit var userActionFab: FloatingActionButton
    private lateinit var micFab: FloatingActionButton
    private lateinit var settingsBtn: ImageButton
    private lateinit var imageBtn: ImageButton
    private lateinit var imageGenerator: ImageGenerator

    // User settings (character + theme)
    private lateinit var settings: KumanSettings
    private var currentCharacter = KumanCharacters.ALL.first()
    private var loadedModelPath: String? = null

    // Voice I/O
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var isListening = false
    private val ttsBuffer = StringBuilder()
    private val thinkFilter = ThinkTagFilter()

    // Face tracking (CameraX + ML Kit, fully on-device)
    private var faceDetector: FaceDetector? = null
    private var cameraProvider: ProcessCameraProvider? = null

    // Arm AI Chat inference engine
    private lateinit var engine: InferenceEngine
    private var generationJob: Job? = null
    private var imageGenJob: Job? = null

    // Conversation states
    private var isModelReady = false
    private val messages = mutableListOf<Message>()
    private val lastAssistantMsg = StringBuilder()
    private val messageAdapter = MessageAdapter(messages)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        onBackPressedDispatcher.addCallback { Log.w(TAG, "Ignore back press for simplicity") }

        // Find views
        faceView = findViewById(R.id.face)
        statusTv = findViewById(R.id.status)
        ggufTv = findViewById(R.id.gguf)
        metaScroll = findViewById(R.id.meta_scroll)
        messagesRv = findViewById(R.id.messages)
        inputRow = findViewById(R.id.input_row)
        messagesRv.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        messagesRv.adapter = messageAdapter
        userInputEt = findViewById(R.id.user_input)
        userActionFab = findViewById(R.id.fab)
        micFab = findViewById(R.id.fab_mic)
        settingsBtn = findViewById(R.id.btn_settings)
        imageBtn = findViewById(R.id.btn_image)
        imageGenerator = ImageGenerator(this)
        settings = KumanSettings(this)
        currentCharacter = settings.character

        setupVoiceIo()
        setupFaceTracking()

        // Arm AI Chat initialization (loads native lib; keep on main to avoid races)
        engine = AiChat.getInferenceEngine(applicationContext)

        // Reflect inference engine state on KUMAN's face
        lifecycleScope.launch {
            engine.state.collect { state ->
                faceView.mood = when (state) {
                    is InferenceEngine.State.Generating -> FaceView.Mood.SPEAKING
                    is InferenceEngine.State.ProcessingUserPrompt,
                    is InferenceEngine.State.ProcessingSystemPrompt -> FaceView.Mood.THINKING
                    is InferenceEngine.State.Error -> FaceView.Mood.ERROR
                    else -> FaceView.Mood.IDLE
                }
            }
        }

        // Try to auto-load the bundled model first; fall back to file picker
        lifecycleScope.launch(Dispatchers.IO) {
            val bundled = ensureBundledModel()
            if (bundled != null) {
                loadModelAndPrompt(bundled)
            } else {
                withContext(Dispatchers.Main) {
                    statusTv.text = "번들 모델이 없습니다. 파일을 선택하세요."
                    userInputEt.hint = "GGUF 모델 파일을 선택하세요"
                }
            }
        }

        // Upon CTA button tapped
        userActionFab.setOnClickListener {
            if (isModelReady) {
                handleUserInput(userInputEt.text.toString())
            } else {
                getContent.launch(arrayOf("*/*"))
            }
        }

        micFab.setOnClickListener {
            if (!isModelReady) {
                Toast.makeText(this, "모델 로딩 후 사용 가능합니다", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (isListening) stopListening() else startListening()
        }

        settingsBtn.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Image generation is on hold: CPU-only SD-Turbo measured >10min
        // per image on-device. Kept in code behind IMAGE_GEN_ENABLED.
        imageBtn.visibility = if (IMAGE_GEN_ENABLED) View.VISIBLE else View.GONE
        imageBtn.setOnClickListener {
            if (!IMAGE_GEN_ENABLED) return@setOnClickListener
            if (!isModelReady) {
                Toast.makeText(this, "모델 로딩 후 사용 가능합니다", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val prompt = userInputEt.text.toString().trim()
            if (IMAGE_RE.containsMatchIn(prompt)) {
                handleUserInput(prompt)
            } else {
                beginImageRequest(
                    prompt.ifEmpty { "a cute robot character, friendly expression" }
                )
            }
        }

        applyTheme()
        applyOrientationLayout()
    }

    override fun onResume() {
        super.onResume()
        val newCharacter = settings.character
        val characterChanged = newCharacter.id != currentCharacter.id
        currentCharacter = newCharacter
        applyTheme()
        if (characterChanged && loadedModelPath != null) {
            reloadForCharacter()
        }
    }

    /**
     * Apply accent color to the face, FABs and user message bubbles.
     */
    private fun applyTheme() {
        val accent = settings.accentColor
        faceView.accentColor = accent
        faceView.eyeStyle = runCatching {
            FaceView.EyeStyle.valueOf(settings.eyeStyle)
        }.getOrDefault(FaceView.EyeStyle.ROUND)
        userActionFab.backgroundTintList = android.content.res.ColorStateList.valueOf(accent)
        messageAdapter.accentColor = accent
    }

    /**
     * Persona changed: unload the model, reload it and send the new
     * system prompt (which must be set right after load).
     */
    private fun reloadForCharacter() {
        val path = loadedModelPath ?: return
        generationJob?.cancel()
        lifecycleScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                statusTv.text = "캐릭터를 바꾸는 중..."
                isModelReady = false
            }
            engine.cleanUp()
            engine.loadModel(path)
            engine.setSystemPrompt(currentCharacter.systemPrompt)
            withContext(Dispatchers.Main) {
                isModelReady = true
                statusTv.text = "준비 완료! 말을 걸어보세요."
                reenableInputs()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_AUDIO_PERMISSION &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
        ) {
            // Camera permission just granted: (re)bind face tracking
            setupFaceTracking()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientationLayout()
    }

    /**
     * Landscape: show only KUMAN's face and the mic button.
     * Portrait: full chat UI.
     */
    private fun applyOrientationLayout() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        faceView.fullscreen = landscape
        if (landscape) {
            faceView.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            statusTv.visibility = View.GONE
            metaScroll.visibility = View.GONE
            messagesRv.visibility = View.GONE
            userInputEt.visibility = View.GONE
            userActionFab.visibility = View.GONE
            micFab.visibility = View.VISIBLE
            imageBtn.visibility = View.GONE
            inputRow.visibility = View.VISIBLE
        } else {
            faceView.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (180 * resources.displayMetrics.density).toInt()
            )
            statusTv.visibility = View.VISIBLE
            metaScroll.visibility = View.VISIBLE
            messagesRv.visibility = View.VISIBLE
            userInputEt.visibility = View.VISIBLE
            userActionFab.visibility = View.VISIBLE
            micFab.visibility = View.VISIBLE
            imageBtn.visibility = if (IMAGE_GEN_ENABLED) View.VISIBLE else View.GONE
            inputRow.visibility = View.VISIBLE
        }
        faceView.requestLayout()
    }

    // ------------------------------------------------------------------
    // Voice input (SpeechRecognizer, offline-preferred) / output (TTS)
    // ------------------------------------------------------------------

    private fun setupVoiceIo() {
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        isListening = true
                        faceView.mood = FaceView.Mood.LISTENING
                        statusTv.text = "듣는 중..."
                    }

                    override fun onResults(results: Bundle?) {
                        val text = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                        Log.i(TAG, "Speech result: $text")
                        if (!text.isNullOrBlank()) {
                            handleUserInput(text)
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit
                    override fun onError(error: Int) {
                        Log.w(TAG, "SpeechRecognizer error: $error")
                        isListening = false
                        if (isModelReady) statusTv.text = "준비 완료! 말을 걸어보세요."
                    }
                })
            }
        } else {
            Log.w(TAG, "SpeechRecognizer not available on this device")
            micFab.isEnabled = false
        }

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(java.util.Locale.KOREAN)
                Log.i(TAG, "TTS Korean language result: $result")
            } else {
                Log.e(TAG, "TTS init failed: $status")
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQ_AUDIO_PERMISSION)
        }
    }

    private fun startListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQ_AUDIO_PERMISSION
            )
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }
        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "startListening failed", e)
            isListening = false
        }
    }

    // ------------------------------------------------------------------
    // Face tracking: front camera -> ML Kit -> KUMAN's pupils follow
    // ------------------------------------------------------------------

    private fun setupFaceTracking() {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.15f)
            .build()
        faceDetector = FaceDetection.getClient(options)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQ_AUDIO_PERMISSION)
            return
        }

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                cameraProvider = providerFuture.get()
                bindFaceAnalysis()
            } catch (e: Exception) {
                Log.e(TAG, "CameraProvider failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindFaceAnalysis() {
        val provider = cameraProvider ?: return
        val detector = faceDetector ?: return

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        analysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { imageProxy ->
            val mediaImage = imageProxy.image
            if (mediaImage == null) {
                imageProxy.close()
                return@setAnalyzer
            }
            val input = InputImage.fromMediaImage(
                mediaImage, imageProxy.imageInfo.rotationDegrees
            )
            detector.process(input)
                .addOnSuccessListener { faces ->
                    updateLookTarget(faces.maxByOrNull {
                        it.boundingBox.width() * it.boundingBox.height()
                    }, imageProxy)
                }
                .addOnFailureListener {
                    faceView.lookTargetX = 0f
                    faceView.lookTargetY = 0f
                }
                .addOnCompleteListener { imageProxy.close() }
        }

        val selector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
            .build()

        try {
            provider.unbindAll()
            provider.bindToLifecycle(this, selector, analysis)
            Log.i(TAG, "Face tracking camera bound")
        } catch (e: Exception) {
            Log.e(TAG, "Camera bind failed", e)
        }
    }

    /**
     * Map the largest detected face to a normalized look direction (-1..1)
     * and feed it to the face view. Front camera image is mirrored on X.
     */
    private fun updateLookTarget(
        face: com.google.mlkit.vision.face.Face?,
        imageProxy: ImageProxy
    ) {
        if (face == null) {
            faceView.lookTargetX = 0f
            faceView.lookTargetY = 0f
            return
        }
        val mediaImage = imageProxy.image ?: return
        val rotation = imageProxy.imageInfo.rotationDegrees
        val srcW = mediaImage.width.toFloat()
        val srcH = mediaImage.height.toFloat()

        // Face center in sensor coordinates
        var fx = face.boundingBox.centerX().toFloat()
        var fy = face.boundingBox.centerY().toFloat()

        // Rotate coordinates into upright image space
        val (uw, uh) = when (rotation) {
            90 -> srcH to srcW
            270 -> srcH to srcW
            else -> srcW to srcH
        }
        when (rotation) {
            90 -> { val nx = srcH - fy; val ny = fx; fx = nx; fy = ny }
            270 -> { val nx = fy; val ny = srcW - fx; fx = nx; fy = ny }
            180 -> { fx = srcW - fx; fy = srcH - fy }
        }

        val nx = ((fx / uw) - 0.5f) * 2f
        val ny = ((fy / uh) - 0.5f) * 2f

        // Mirror X for the front camera so it feels like a mirror
        faceView.lookTargetX = (-nx).coerceIn(-1f, 1f)
        faceView.lookTargetY = ny.coerceIn(-1f, 1f)
    }

    private fun stopListening() {
        speechRecognizer?.stopListening()
        isListening = false
        if (isModelReady) statusTv.text = "준비 완료! 말을 걸어보세요."
    }

    /**
     * Feed newly generated (think-filtered) text into the TTS queue,
     * speaking one sentence at a time.
     */
    private fun pumpTts() {
        val ttsInstance = tts ?: return
        while (true) {
            val end = ttsBuffer.indexOfFirst { it == '.' || it == '!' || it == '?' || it == '…' || it == '\n' }
            if (end < 0) break
            val sentence = ttsBuffer.substring(0, end + 1).trim()
            ttsBuffer.delete(0, end + 1)
            if (sentence.length >= 2) {
                ttsInstance.speak(sentence, TextToSpeech.QUEUE_ADD, null, "kuman_${System.currentTimeMillis()}")
            }
        }
    }

    private fun flushTts() {
        val ttsInstance = tts ?: return
        val rest = ttsBuffer.toString().trim()
        ttsBuffer.clear()
        if (rest.isNotEmpty()) {
            ttsInstance.speak(rest, TextToSpeech.QUEUE_ADD, null, "kuman_${System.currentTimeMillis()}")
        }
    }

    private val getContent = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        Log.i(TAG, "Selected file uri:\n $uri")
        uri?.let { handleSelectedModel(it) }
    }

    /**
     * Copy the bundled GGUF from assets into private storage, if present.
     */
    private suspend fun ensureBundledModel(): File? = withContext(Dispatchers.IO) {
        val assetName = "$DIRECTORY_MODELS/$BUNDLED_MODEL_NAME"
        val modelFile = File(ensureModelsDirectory(), BUNDLED_MODEL_NAME)
        try {
            assets.open(assetName).use { input ->
                if (!modelFile.exists() || modelFile.length() != BUNDLED_MODEL_BYTES) {
                    Log.i(TAG, "Copying bundled model to ${modelFile.path}")
                    withContext(Dispatchers.Main) { statusTv.text = "모델 파일을 준비하는 중..." }
                    FileOutputStream(modelFile).use { input.copyTo(it) }
                }
            }
            modelFile.takeIf { it.exists() && it.length() > 0 }
        } catch (e: Exception) {
            Log.w(TAG, "No bundled model asset at $assetName", e)
            null
        }
    }

    /**
     * Handles the file Uri from [getContent] result
     */
    private fun handleSelectedModel(uri: Uri) {
        userActionFab.isEnabled = false
        userInputEt.hint = "GGUF 정보를 읽는 중..."
        ggufTv.text = "선택한 GGUF 모델: \n$uri"

        lifecycleScope.launch(Dispatchers.IO) {
            contentResolver.openInputStream(uri)?.use {
                GgufMetadataReader.create().readStructuredMetadata(it)
            }?.let { metadata ->
                Log.i(TAG, "GGUF parsed: \n$metadata")
                withContext(Dispatchers.Main) {
                    ggufTv.text = metadata.toString()
                }

                val modelName = metadata.filename() + FILE_EXTENSION_GGUF
                contentResolver.openInputStream(uri)?.use { input ->
                    ensureModelFile(modelName, input)
                }?.let { modelFile ->
                    loadModelAndPrompt(modelFile)
                }
            } ?: run {
                withContext(Dispatchers.Main) {
                    statusTv.text = "GGUF 메타데이터를 읽지 못했습니다."
                    userActionFab.isEnabled = true
                }
            }
        }
    }

    /**
     * Prepare the model file within app's private storage
     */
    private suspend fun ensureModelFile(modelName: String, input: InputStream) =
        withContext(Dispatchers.IO) {
            File(ensureModelsDirectory(), modelName).also { file ->
                if (!file.exists()) {
                    Log.i(TAG, "Start copying file to $modelName")
                    withContext(Dispatchers.Main) {
                        userInputEt.hint = "모델 파일 복사 중..."
                    }
                    FileOutputStream(file).use { input.copyTo(it) }
                    Log.i(TAG, "Finished copying file to $modelName")
                }
            }
        }

    /**
     * Load the model, then send KUMAN's system prompt.
     * The system prompt MUST be set right after the model is loaded.
     */
    private suspend fun loadModelAndPrompt(modelFile: File) =
        withContext(Dispatchers.IO) {
            Log.i(TAG, "Loading model ${modelFile.name}")
            withContext(Dispatchers.Main) {
                statusTv.text = "모델을 불러오는 중..."
                userInputEt.hint = "모델 로딩 중..."
            }
            engine.loadModel(modelFile.path)
            loadedModelPath = modelFile.path
            engine.setSystemPrompt(currentCharacter.systemPrompt)

            withContext(Dispatchers.Main) {
                isModelReady = true
                statusTv.text = "준비 완료! 말을 걸어보세요."
                userInputEt.hint = "쿠만에게 할 말을 입력하세요"
                userInputEt.isEnabled = true
                userActionFab.setImageResource(R.drawable.outline_send_24)
                userActionFab.isEnabled = true
            }
        }

    /**
     * Validate and send the user message into [InferenceEngine]
     */
    private fun handleUserInput(userMsg: String = userInputEt.text.toString()) {
        if (userMsg.isEmpty()) {
            Toast.makeText(this, "메시지가 비어 있습니다!", Toast.LENGTH_SHORT).show()
        } else {
            userInputEt.text = null
            userInputEt.isEnabled = false
            userActionFab.isEnabled = false
            micFab.isEnabled = false
            imageBtn.isEnabled = false
            stopListening()
            tts?.stop()
            ttsBuffer.clear()
            thinkFilter.reset()

            messages.add(Message(UUID.randomUUID().toString(), userMsg, true))
            lastAssistantMsg.clear()
            messages.add(Message(UUID.randomUUID().toString(), lastAssistantMsg.toString(), false))

            when {
                // Time/date questions are answered locally — nothing leaves the device
                TIME_DATE_RE.containsMatchIn(userMsg) -> answerTimeDateLocally()
                // Image generation request ("그려줘", "그림", ...)
                IMAGE_GEN_ENABLED && IMAGE_RE.containsMatchIn(userMsg) ->
                    handleImageRequest(userMsg)
                // Weather needs one external call: only the coordinates are sent
                WEATHER_RE.containsMatchIn(userMsg) -> fetchWeatherThenGenerate(userMsg)
                // Web search: only the question text is sent, never chat history
                SEARCH_RE.containsMatchIn(userMsg) -> fetchWebThenGenerate(userMsg)
                else -> runGeneration(userMsg)
            }
        }
    }

    /**
     * Re-enable inputs after a locally-handled (non-LLM) answer.
     */
    private fun reenableInputs() {
        userInputEt.isEnabled = true
        userActionFab.isEnabled = true
        micFab.isEnabled = true
        imageBtn.isEnabled = true
    }

    private fun answerTimeDateLocally() {
        val answer = currentDateTimeText()
        lastAssistantMsg.append(answer)
        messages[messages.lastIndex] = messages.last().copy(content = answer)
        messageAdapter.notifyItemChanged(messages.lastIndex)
        messagesRv.scrollToPosition(messages.lastIndex)
        tts?.speak(answer, TextToSpeech.QUEUE_FLUSH, null, "kuman_time")
        reenableInputs()
    }

    private fun fetchWeatherThenGenerate(userMsg: String) {
        lifecycleScope.launch {
            statusTv.text = "날씨 정보를 가져오는 중..."
            val latLon = withContext(Dispatchers.IO) { lastLocationLatLon() }
            val summary = withContext(Dispatchers.IO) { WeatherFetcher.fetchSummary(latLon) }
            val prompt = if (summary != null) {
                "$userMsg\n\n[시스템 참고] 현재 날씨 정보: $summary"
            } else {
                userMsg
            }
            statusTv.text = "준비 완료! 말을 걸어보세요."
            runGeneration(prompt)
        }
    }

    private fun fetchWebThenGenerate(userMsg: String) {
        lifecycleScope.launch {
            statusTv.text = "웹에서 검색하는 중..."
            val result = withContext(Dispatchers.IO) { WebSearcher.search(userMsg) }
            val prompt = if (result != null) {
                "$userMsg\n\n[시스템 참고] 웹 검색 결과: $result"
            } else {
                userMsg
            }
            statusTv.text = "준비 완료! 말을 걸어보세요."
            runGeneration(prompt)
        }
    }

    /**
     * Image button entry point: same preamble as [handleUserInput]
     * (user bubble + empty assistant placeholder), then generation.
     */
    private fun beginImageRequest(userMsg: String) {
        if (userMsg.isEmpty()) return
        userInputEt.text = null
        userInputEt.isEnabled = false
        userActionFab.isEnabled = false
        micFab.isEnabled = false
        imageBtn.isEnabled = false
        stopListening()
        tts?.stop()
        ttsBuffer.clear()
        thinkFilter.reset()

        messages.add(Message(UUID.randomUUID().toString(), userMsg, true))
        lastAssistantMsg.clear()
        messages.add(Message(UUID.randomUUID().toString(), "", false))
        messageAdapter.notifyItemRangeInserted(messages.size - 2, 2)
        messagesRv.scrollToPosition(messages.size - 1)

        handleImageRequest(userMsg)
    }

    /**
     * Image generation: translate the (usually Korean) request into an
     * English SD prompt via the local LLM, then run SD-Turbo on-device.
     * The user message + empty assistant placeholder were already added
     * by [handleUserInput]; the placeholder becomes the image bubble.
     */
    private fun handleImageRequest(userMsg: String) {
        // Show progress inside the assistant bubble right away — the
        // pipeline (translate -> copy/load -> generate) can take minutes
        // on first run and must not look frozen.
        runCatching {
            val idx = messages.lastIndex
            if (idx >= 0 && !messages[idx].isUser) {
                messages[idx] = messages[idx].copy(content = "🎨 이미지를 그리고 있어요…")
                messageAdapter.notifyItemChanged(idx)
            }
        }
        imageGenJob = lifecycleScope.launch {
            try {
                statusTv.text = "영어 프롬프트로 변환하는 중..."
                val enPrompt = translateToSdPrompt(userMsg)
                    .ifEmpty { userMsg }

                statusTv.text = "이미지 모델을 준비하는 중... (첫 실행 시 오래 걸립니다)"
                val ready = imageGenerator.ensureModelLoaded()
                if (!ready) {
                    failImageMessage("이미지 모델을 불러오지 못했어요.")
                    return@launch
                }

                statusTv.text = "이미지 생성 중... (30초~1분 정도 걸려요)"
                val bitmap = imageGenerator.generate(enPrompt)
                if (bitmap != null) {
                    val idx = messages.lastIndex
                    messages[idx] = Message(
                        messages[idx].id,
                        enPrompt.take(80),
                        isUser = false,
                        bitmap = bitmap
                    )
                    messageAdapter.notifyItemChanged(idx)
                    messagesRv.scrollToPosition(idx)
                    tts?.speak("그림을 그렸어요!", TextToSpeech.QUEUE_FLUSH, null, "kuman_img")
                    statusTv.text = "준비 완료! 말을 걸어보세요."
                } else {
                    failImageMessage("이미지 생성에 실패했어요. 다시 시도해 주세요.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Image generation failed", e)
                failImageMessage("이미지 생성 중 오류가 발생했어요.")
            } finally {
                reenableInputs()
            }
        }
    }

    private fun failImageMessage(text: String) {
        val idx = messages.lastIndex
        if (idx >= 0 && !messages[idx].isUser) {
            messages[idx] = messages[idx].copy(content = text)
            messageAdapter.notifyItemChanged(idx)
            messagesRv.scrollToPosition(idx)
        }
        statusTv.text = "준비 완료! 말을 걸어보세요."
    }

    /**
     * Use the local LLM to turn a Korean image request into a short
     * English text-to-image prompt. Returns the raw text when the
     * request contains no Hangul.
     */
    private suspend fun translateToSdPrompt(text: String): String {
        if (!HANGUL_RE.containsMatchIn(text)) return text
        val sb = StringBuilder()
        val filter = ThinkTagFilter()
        val instruction =
            "Rewrite the following Korean image request as ONE short English " +
                "text-to-image prompt: comma-separated visual details only, " +
                "no explanation, no quotes. Request: $text"
        try {
            engine.sendUserPrompt(instruction, predictLength = 384).collect { token ->
                sb.append(filter.feed(token))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Prompt translation failed, using raw text", e)
        }
        return sb.toString()
            .lineSequence().firstOrNull { it.isNotBlank() }
            ?.trim('"', '\'', ' ', '.', '!', '?')
            ?.take(200)
            .orEmpty()
    }

    /**
     * Run LLM generation for [prompt]. The user-visible message bubble was
     * already added by [handleUserInput].
     */
    private fun runGeneration(prompt: String) {
        generationJob = lifecycleScope.launch(Dispatchers.Default) {
            engine.sendUserPrompt(prompt)
                .onCompletion {
                    withContext(Dispatchers.Main) {
                        reenableInputs()
                        flushTts()
                    }
                }.collect { token ->
                    withContext(Dispatchers.Main) {
                        val visible = thinkFilter.feed(token)
                        if (visible.isEmpty()) return@withContext
                        lastAssistantMsg.append(visible)
                        ttsBuffer.append(visible)

                        val messageCount = messages.size
                        check(messageCount > 0 && !messages[messageCount - 1].isUser)

                        messages.removeAt(messageCount - 1).copy(
                            content = lastAssistantMsg.toString()
                        ).let { messages.add(it) }

                        messageAdapter.notifyItemChanged(messages.size - 1)
                        messagesRv.scrollToPosition(messages.size - 1)
                        pumpTts()
                    }
                }
        }
    }

    /**
     * Best-effort last-known location as "lat,lon" for the weather API.
     * Returns null when permission is missing or no fix is available —
     * WeatherFetcher then falls back to a default city.
     */
    private fun lastLocationLatLon(): String? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return null
        val lm = getSystemService(LocationManager::class.java) ?: return null
        val loc = lm.getProviders(true)
            .firstNotNullOfOrNull { lm.getLastKnownLocation(it) } ?: return null
        return "${loc.latitude},${loc.longitude}"
    }

    /**
     * Create the `models` directory if not exist.
     */
    private fun ensureModelsDirectory() =
        File(filesDir, DIRECTORY_MODELS).also {
            if (it.exists() && !it.isDirectory) { it.delete() }
            if (!it.exists()) { it.mkdir() }
        }

    // NOTE: generation jobs are intentionally NOT cancelled in onStop —
    // briefly backgrounding the app must not kill an in-flight answer.
    // lifecycleScope still cancels everything on onDestroy.

    override fun onDestroy() {
        stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        cameraProvider?.unbindAll()
        cameraProvider = null
        faceDetector?.close()
        faceDetector = null
        engine.destroy()
        SdBridge.cancel()
        SdBridge.unload()
        super.onDestroy()
    }

    companion object {
        private val TAG = MainActivity::class.java.simpleName

        private const val DIRECTORY_MODELS = "models"
        private const val BUNDLED_MODEL_NAME = "kuman-mini.gguf"
        // EXAONE-3.5-2.4B-Instruct-Q4_K_M.gguf — bump when swapping models
        private const val BUNDLED_MODEL_BYTES = 1644918272L
        private const val FILE_EXTENSION_GGUF = ".gguf"
        private const val REQ_AUDIO_PERMISSION = 1001
        private const val IMAGE_GEN_ENABLED = false

        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        private val TIME_DATE_RE = Regex(
            "몇 시|무슨 요일|오늘.*날짜|날짜.*(알려|궁금|뭐)|몇 월|몇 일|연도|현재 시간|지금 시간"
        )
        private val WEATHER_RE = Regex(
            "날씨|우산|기온|미세먼지|황사|태풍|폭염|한파|습도|비가|눈이|덥|춥|바람"
        )
        private val SEARCH_RE = Regex(
            "검색|찾아봐|찾아줘|알아봐|뭐야|누구|언제|어디|왜|어떻게|뜻이|의미|누가"
        )
        private val IMAGE_RE = Regex(
            "그려|그림|이미지|드로잉|픽쳐|draw|paint|picture"
        )
        private val HANGUL_RE = Regex("[가-힯]")

        private fun currentDateTimeText(): String {
            val now = java.time.LocalDateTime.now()
            val dow = now.dayOfWeek.getDisplayName(
                java.time.format.TextStyle.FULL, java.util.Locale.KOREAN
            )
            return "지금은 ${now.year}년 ${now.monthValue}월 ${now.dayOfMonth}일 " +
                "$dow ${now.hour}시 ${now.minute}분입니다."
        }

    }
}

/**
 * Streams generated tokens and strips `<think>...</think>` blocks so the
 * reasoning trace is neither shown in chat nor spoken by TTS.
 * Handles tags split across token boundaries.
 */
private class ThinkTagFilter {
    private val buf = StringBuilder()
    private var inThink = false

    private val OPEN = "<think>"
    private val CLOSE = "</think>"

    fun feed(piece: String): String {
        buf.append(piece)
        val out = StringBuilder()
        while (true) {
            if (inThink) {
                val i = buf.indexOf(CLOSE)
                if (i >= 0) {
                    buf.delete(0, i + CLOSE.length)
                    inThink = false
                } else {
                    val keep = CLOSE.length - 1
                    if (buf.length > keep) buf.delete(0, buf.length - keep)
                    break
                }
            } else {
                val i = buf.indexOf(OPEN)
                if (i >= 0) {
                    out.append(buf, 0, i)
                    buf.delete(0, i + OPEN.length)
                    inThink = true
                } else {
                    val keep = OPEN.length - 1
                    if (buf.length > keep) {
                        out.append(buf, 0, buf.length - keep)
                        buf.delete(0, buf.length - keep)
                    }
                    break
                }
            }
        }
        return out.toString()
    }

    fun reset() {
        buf.clear()
        inThink = false
    }
}

fun GgufMetadata.filename() = when {
    basic.name != null -> {
        basic.name?.let { name ->
            basic.sizeLabel?.let { size ->
                "$name-$size"
            } ?: name
        }
    }
    architecture?.architecture != null -> {
        architecture?.architecture?.let { arch ->
            basic.uuid?.let { uuid ->
                "$arch-$uuid"
            } ?: "$arch-${System.currentTimeMillis()}"
        }
    }
    else -> {
        "model-${System.currentTimeMillis().toHexString()}"
    }
}
