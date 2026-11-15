package com.jarvis.assistant

import android.Manifest
import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class JarvisState { IDLE, LISTENING, THINKING, SPEAKING }
data class ChatMessage(val text: String, val fromUser: Boolean)
data class PendingAction(val question: String, val actions: List<Action>)

/** The brain: voice loop + AI loop + confirmation gate. Lives in the Application, used by UI and service. */
class JarvisEngine(private val app: Application) : SpeechManager.Callback {

    private enum class Mode { OFF, WAKE, COMMAND }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val store = SecureStore(app)
    private val tts = TtsManager(app)
    private val ai = AiClient(store)
    private val executor = ActionExecutor(app)
    private val speech = SpeechManager(app, this)

    private val _state = MutableStateFlow(JarvisState.IDLE)
    private val _messages = MutableStateFlow(
        listOf(ChatMessage("Hello. Say \"Hey Jarvis\" or tap the core.", false))
    )
    private val _partial = MutableStateFlow("")
    private val _level = MutableStateFlow(0f)
    private val _wake = MutableStateFlow(false)
    private val _pending = MutableStateFlow<PendingAction?>(null)
    val state = _state.asStateFlow()
    val messages = _messages.asStateFlow()
    val partial = _partial.asStateFlow()
    val level = _level.asStateFlow()
    val wake = _wake.asStateFlow()
    val pending = _pending.asStateFlow()

    private val convo = mutableListOf<Pair<String, String>>()
    private var mode = Mode.OFF
    private var job: Job? = null
    private var followJob: Job? = null
    private var wakeJob: Job? = null
    private var lastLang = "hi"

    private val aliases = listOf(
        "jarvis", "jarwis", "jarves", "jaarvis", "जार्विस", "जारविस", "जर्विस", "जार्विश", "जारवीस", "जार्विज"
    )
    private val stopWords = listOf("stop", "स्टॉप", "स्टाप", "रुको", "ruko", "band karo", "बंद", "रुक")

    fun setWake(on: Boolean) {
        if (on) {
            if (!SystemInfo.has(app, Manifest.permission.RECORD_AUDIO)) {
                addMsg("Microphone permission is needed for the wake word.", false); return
            }
            _wake.value = true
            ContextCompat.startForegroundService(app, Intent(app, JarvisService::class.java))
            if (_state.value == JarvisState.IDLE) { mode = Mode.WAKE; speech.start() }
        } else {
            _wake.value = false
            app.stopService(Intent(app, JarvisService::class.java))
            if (mode == Mode.WAKE) { speech.cancel(); mode = Mode.OFF }
        }
    }

    fun micTapped() {
        when (_state.value) {
            JarvisState.SPEAKING -> { followJob?.cancel(); tts.stop(); startCommand() }
            JarvisState.LISTENING -> { speech.cancel(); idleOrWake() }
            JarvisState.THINKING -> emergencyStop()
            JarvisState.IDLE -> startCommand()
        }
    }

    fun sendText(t: String) {
        if (t.isNotBlank()) handleUtterance(t.trim())
    }

    fun resolvePending(yes: Boolean) = handleUtterance(if (yes) "yes" else "no")

    fun clearMemory() { store.clearMemory(); addMsg("Memory cleared.", false) }

    fun emergencyStop() {
        job?.cancel(); followJob?.cancel(); wakeJob?.cancel()
        tts.stop(); speech.cancel()
        _pending.value = null; _partial.value = ""
        addMsg("Stopped.", false)
        idleOrWake()
    }

    private fun startCommand() {
        if (!SystemInfo.has(app, Manifest.permission.RECORD_AUDIO)) {
            addMsg("Microphone permission is needed.", false); idleOrWake(); return
        }
        mode = Mode.COMMAND
        _partial.value = ""
        _state.value = JarvisState.LISTENING
        speech.start()
    }

    private fun idleOrWake() {
        _state.value = JarvisState.IDLE
        _level.value = 0f
        mode = if (_wake.value) Mode.WAKE else Mode.OFF
        if (mode == Mode.WAKE) restartWake()
    }

    private fun restartWake(delayMs: Long = 300) {
        wakeJob?.cancel()
        wakeJob = scope.launch { delay(delayMs); if (mode == Mode.WAKE) speech.start() }
    }

    override fun onPartial(text: String) { _partial.value = text }
    override fun onLevel(level: Float) { _level.value = level }

    override fun onError(code: Int) {
        when {
            code == -1 || code == 9 -> {
                _wake.value = false
                addMsg("Speech recognition unavailable (check microphone permission / Google app).", false)
                idleOrWake()
            }
            mode == Mode.WAKE -> restartWake(if (code == 8) 1500 else 300)
            mode == Mode.COMMAND -> idleOrWake()
        }
    }

    override fun onResult(text: String) {
        _partial.value = ""
        val t = text.trim()
        if (t.isEmpty()) { onError(7); return }
        if (isStopPhrase(t, _state.value == JarvisState.SPEAKING)) { emergencyStop(); return }
        when (mode) {
            Mode.WAKE -> {
                if (_state.value != JarvisState.IDLE) { restartWake(); return }
                val rest = stripWake(t)
                when {
                    rest == null -> restartWake()
                    rest.isBlank() -> { restartWake(); speak(tr("जी, बताइए?", "Yes?")) }
                    else -> handleUtterance(rest)
                }
            }
            Mode.COMMAND -> handleUtterance(t)
            Mode.OFF -> {}
        }
    }

    private fun stripWake(t: String): String? {
        val s = t.lowercase()
        val hit = aliases.mapNotNull { a -> s.indexOf(a).takeIf { it >= 0 }?.let { it to a } }.minByOrNull { it.first }
            ?: return null
        return s.substring(hit.first + hit.second.length).trim(' ', ',', '.', '!', '?', '।')
    }

    private fun isStopPhrase(t: String, speaking: Boolean): Boolean {
        val s = t.lowercase()
        val hasStop = stopWords.any { s.contains(it) }
        val hasName = aliases.any { s.contains(it) }
        val short = s.split(Regex("\\s+")).size <= 3
        return hasStop && (hasName || (speaking && short))
    }

    private fun tokens(t: String) = t.lowercase().split(Regex("[\\s,.!?।]+")).filter { it.isNotEmpty() }
    private fun isNo(t: String) = tokens(t).any { it in setOf("no", "nahi", "nahin", "cancel", "dont", "don't", "नहीं", "नही", "मत", "रद्द", "कैंसल", "ना", "stop") }
    private fun isYes(t: String) = tokens(t).any { it in setOf("yes", "yeah", "yep", "ok", "okay", "sure", "confirm", "haan", "han", "हाँ", "हां", "ठीक", "जी", "करो", "कर", "भेजो") }

    private fun tr(hi: String, en: String) = if (lastLang == "hi") hi else en

    private fun handleUtterance(text: String) {
        tts.stop(); speech.cancel(); followJob?.cancel()
        mode = if (_wake.value) Mode.WAKE else Mode.OFF
        if (mode == Mode.WAKE) restartWake()
        lastLang = if (text.any { it in '\u0900'..'\u097F' }) "hi" else "en"
        addMsg(text, true)
        job?.cancel()
        job = scope.launch {
            _state.value = JarvisState.THINKING
            try {
                val p = _pending.value
                _pending.value = null
                when {
                    p != null && isNo(text) -> speak(tr("ठीक है, रद्द कर दिया।", "Okay, cancelled."))
                    p != null && isYes(text) -> confirmed(p)
                    else -> { convo.add("user" to text); loop() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                speak(tr("माफ़ कीजिए, गड़बड़ हो गई: ${e.message}", "Sorry, something went wrong: ${e.message}"))
            }
        }
    }

    private suspend fun confirmed(p: PendingAction) {
        val results = p.actions.map { executor.execute(it) }
        if (results.any { it.feedback }) {
            convo.add("user" to "USER_CONFIRMED. TOOL_RESULTS:\n" + results.joinToString("\n") { it.text })
            delay(600); loop()
        } else speak(tr("हो गया।", "Done."))
    }

    private suspend fun loop() {
        var rounds = 0
        while (true) {
            while (convo.size > 24) convo.removeAt(0)
            while (convo.isNotEmpty() && convo[0].first != "user") convo.removeAt(0)

            val reply = try {
                ai.ask(convo, store.memory().joinToString("; ").ifBlank { "none" })
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                if (convo.lastOrNull()?.first == "user") convo.removeAt(convo.lastIndex)
                throw e
            }
            convo.add("assistant" to reply.raw)
            reply.memory?.let { store.addMemory(it); addMsg("Saved to memory: $it", false) }

            if (reply.actions.isNotEmpty() && (reply.confirm || reply.actions.any { it.sensitive })) {
                _pending.value = PendingAction(reply.speech, reply.actions)
                speak(reply.speech.ifBlank { tr("क्या मैं आगे बढ़ूँ?", "Shall I proceed?") })
                return
            }
            val results = reply.actions.map { executor.execute(it) }
            if (results.none { it.feedback } || ++rounds >= 6) { speak(reply.speech); return }
            convo.add("user" to "TOOL_RESULTS:\n" + results.joinToString("\n") { it.text })
            delay(700)
        }
    }

    private fun speak(text: String) {
        if (text.isBlank()) { idleOrWake(); return }
        addMsg(text, false)
        _state.value = JarvisState.SPEAKING
        tts.speak(text) {
            followJob = scope.launch { delay(250); if (_state.value == JarvisState.SPEAKING) startCommand() }
        }
    }

    private fun addMsg(t: String, user: Boolean) {
        _messages.value = (_messages.value + ChatMessage(t, user)).takeLast(100)
    }
}
