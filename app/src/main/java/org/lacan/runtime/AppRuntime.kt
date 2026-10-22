package org.lacan.runtime

import android.Manifest
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.lacan.access.LacanAccessibilityService
import org.lacan.asr.AsrEngine
import org.lacan.asr.EngineFactory
import org.lacan.audio.AudioCapture
import org.lacan.core.*
import org.lacan.history.*
import org.lacan.models.*
import org.lacan.ui.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** App-scoped ownership: UI never owns microphone/model lifetime. All inference is serialized. */
class AppRuntime(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = context.getSharedPreferences("lacan", Context.MODE_PRIVATE)
    private val repository = ModelRepository(context)
    private val history = HistoryStore(context)
    private val engineMutex = Mutex()
    private val nativeDispatcher = Dispatchers.IO.limitedParallelism(1)
    private var engine: AsrEngine? = null
    private var engineKey: String? = null
    private val gate = SessionGate()
    private var selected = ModelCatalog.activeSelection(prefs.getString("model", "") ?: "")
    private val mutable = MutableStateFlow(ScreenState(enabled = prefs.getBoolean("enabled", true), language = prefs.getString("language", "ru") ?: "ru"))
    val state: StateFlow<ScreenState> = mutable.asStateFlow()
    @Volatile private var current: Session? = null
    private var modelMutation = false
    private var downloadJob: Job? = null
    private var warmJob: Job? = null
    private class Session(val token: Long, val spec: ModelSpec, val language: String, val spool: AudioSpool) {
        val id = UUID.randomUUID().toString()
        val started = System.currentTimeMillis()
        val monotonicStarted = SystemClock.elapsedRealtime()
        private val stoppedAt = AtomicLong(0L)
        fun stopElapsed(): Long {
            stoppedAt.compareAndSet(0L, SystemClock.elapsedRealtime())
            return (stoppedAt.get() - monotonicStarted).coerceAtLeast(0)
        }
        val cancelled = AtomicBoolean(false)
        val segmenter = Segmenter()
        val queue = Channel<AudioSegment>(Channel.UNLIMITED) // small descriptors, never audio arrays
        val captureEnded = CompletableDeferred<Unit>()
        @Volatile var reason = "user"
        @Volatile var capture: AudioCapture? = null
        @Volatile var recording = true
    }
    init {
        prefs.edit().putString("model", selected).apply()
        // Process death never resumes microphone or replays insertion.
        File(context.cacheDir, "audio").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        scope.launch {
            withContext(Dispatchers.IO) { history.recoverDrafts() }
            history.entries.collect { entries ->
                val date = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
                mutable.update { it.copy(history = entries.map { e -> HistoryCard(e.id, date.format(Date(e.endedAt)), e.text, "${e.model} · ${e.durationMs / 1000} с · ${e.reason}") }) }
            }
        }
        refreshPermissions(); refreshModels(); warmModel()
    }
    fun message(text: String) { mutable.update { it.copy(status = text) } }
    fun refreshPermissions() {
        fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        mutable.update { it.copy(microphoneGranted = granted(Manifest.permission.RECORD_AUDIO),
            notificationsGranted = granted(Manifest.permission.POST_NOTIFICATIONS) && context.getSystemService(NotificationManager::class.java).areNotificationsEnabled() && context.getSystemService(NotificationManager::class.java).getNotificationChannel("dictation")?.importance != NotificationManager.IMPORTANCE_NONE, overlayGranted = Settings.canDrawOverlays(context),
            accessibilityEnabled = LacanAccessibilityService.instance != null) }
        refreshReadiness()
    }
    private fun refreshReadiness() {
        mutable.update { it.copy(readyToRecord = it.setupBlockReason() == null &&
            !modelMutation && engine != null && engineKey == "$selected:${it.language}") }
    }
    private fun refreshModels() {
        mutable.update { it.copy(models = ModelCatalog.activeModels.map { m -> ModelCard(m.id, m.title, m.description,
            ((m.totalBytes + 999999) / 1000000).toInt(), repository.isInstalled(m), m.id == selected, m.localImportFile != null, m.downloadable) }) }
        refreshReadiness()
    }
    fun setEnabled(value: Boolean) {
        if (state.value.busy || modelMutation) return
        prefs.edit().putBoolean("enabled", value).apply(); mutable.update { it.copy(enabled = value) }
        refreshReadiness()
        if (value) warmModel() else releaseIdleModel()
    }
    fun setLanguage(value: String) {
        if (state.value.busy || modelMutation || value !in listOf("ru", "en", "auto")) return
        prefs.edit().putString("language", value).apply(); mutable.update { it.copy(language = value) }; warmModel()
    }
    fun selectModel(id: String) {
        if (state.value.busy || modelMutation) return
        val spec = ModelCatalog.activeModels.find { it.id == id } ?: return
        if (!repository.isInstalled(spec)) return
        selected = id; prefs.edit().putString("model", id).apply(); refreshModels(); warmModel()
    }
    fun download(id: String) {
        val spec = ModelCatalog.activeModels.find { it.id == id } ?: return
        if (!spec.downloadable) { message("Для этой модели нужен импорт локального файла .mnn"); return }
        transferModel(spec, importing = false) { progress -> repository.download(spec, progress) }
    }
    fun importModel(id: String, uri: Uri) {
        val spec = ModelCatalog.activeModels.find { it.id == id } ?: return
        if (spec.localImportFile == null) { message("Эта модель не поддерживает импорт файла"); return }
        transferModel(spec, importing = true) { progress -> repository.importLocal(spec, uri, progress) }
    }
    private fun transferModel(spec: ModelSpec, importing: Boolean, transfer: suspend ((Float) -> Unit) -> Unit) {
        if (state.value.busy || modelMutation || downloadJob?.isActive == true) return
        modelMutation = true
        warmJob?.cancel()
        mutable.update { it.copy(downloadingId = spec.id, downloadProgress = 0f, status = if (importing) "Импорт и проверка модели…" else "Загрузка модели…") }
        refreshReadiness()
        downloadJob = scope.launch {
            var installed = false
            try {
                engineMutex.withLock {
                    closeEngine()
                    transfer { progress -> mutable.update { it.copy(downloadProgress = progress) } }
                }
                installed = true
                message(if (importing) "Модель импортирована и проверена" else "Модель скачана и проверена")
            } catch (_: CancellationException) { message(if (importing) "Импорт отменён" else "Загрузка отменена") }
            catch (_: Exception) {
                message(if (importing) "Не удалось импортировать модель. Нужен точный файл .mnn из каталога и достаточно свободного места."
                    else "Не удалось скачать модель. Проверьте сеть и свободное место; попробуйте снова.")
            } finally {
                modelMutation = false
                mutable.update { it.copy(downloadingId = null) }
                refreshModels()
                if (installed && selected.isBlank()) {
                    selected = spec.id
                    prefs.edit().putString("model", selected).apply()
                    refreshModels()
                }
                warmModel()
            }
        }
    }
    fun cancelDownload() { downloadJob?.cancel() }
    fun removeModel(id: String) {
        if (state.value.busy || modelMutation || state.value.downloadingId == id) return
        val spec = ModelCatalog.activeModels.find { it.id == id } ?: return
        modelMutation = true
        scope.launch {
          try {
            engineMutex.withLock {
                if (state.value.busy) return@withLock
                if (id == selected) { closeEngine(); selected = ""; prefs.edit().remove("model").apply() }
                withContext(Dispatchers.IO) { repository.remove(spec) }
            }
            refreshModels(); message("Модель удалена")
          } catch (_: Exception) { message("Не удалось удалить модель") }
          finally { modelMutation = false; refreshModels() }
        }
    }
    private suspend fun engineFor(spec: ModelSpec, language: String): AsrEngine {
        val key = "${spec.id}:$language"
        if (engineKey != key) {
            closeEngine()
            check(repository.verifyInstalled(spec)) { "Model integrity verification failed" }
            // Publish the native handle even if the waiting UI job is cancelled: otherwise it leaks.
            withContext(NonCancellable + nativeDispatcher) { engine = EngineFactory.create(spec, repository.directory(spec), language) }
            engineKey = key
            refreshReadiness()
        }
        return checkNotNull(engine)
    }
    private suspend fun closeEngine() {
        withContext(nativeDispatcher) { engine?.close(); engine = null; engineKey = null }
        refreshReadiness()
    }
    private fun warmModel() {
        if (!state.value.enabled || state.value.busy || modelMutation) return
        val spec = ModelCatalog.activeModels.find { it.id == selected } ?: return
        if (!repository.isInstalled(spec)) return
        refreshReadiness()
        val language = state.value.language
        warmJob?.cancel()
        warmJob = scope.launch {
            try {
                engineMutex.withLock {
                    if (!state.value.busy && state.value.enabled && !modelMutation) { message(if (spec.engine.endsWith("-gpu")) "Подготовка модели на GPU…" else "Загрузка модели в память…"); engineFor(spec, language); if (!state.value.busy) message("Готово к диктовке") }
                }
            } catch (_: CancellationException) { }
            catch (_: Exception) { message(if (spec.engine.endsWith("-gpu")) "Не удалось запустить модель на GPU. Выберите модель для CPU." else "Не удалось загрузить модель. Попробуйте выбрать более лёгкую.") }
        }
    }
    fun releaseIdleModel() {
        scope.launch { engineMutex.withLock { if (!state.value.busy) closeEngine() } }
    }
    fun startCapture(onFinished: () -> Unit) {
        refreshPermissions()
        val spec = ModelCatalog.activeModels.find { it.id == selected }
        if (modelMutation || !state.value.enabled || !state.value.microphoneGranted || spec == null || !repository.isInstalled(spec)) {
            message("Для записи включите Lacan, разрешите микрофон и выберите установленную модель"); onFinished(); return
        }
        if (locked()) { message("Разблокируйте экран для диктовки"); onFinished(); return }
        if (!state.value.notificationsGranted) { message("Для фоновой диктовки разрешите уведомления Lacan: в них доступны Стоп и Отмена"); onFinished(); return }
        val token = gate.start() ?: return
        val s = try { Session(token, spec, state.value.language, AudioSpool(File(context.cacheDir, "audio/${UUID.randomUUID()}.pcm"))) }
        catch (_: Exception) { gate.finish(token); message("Нет места для временной записи"); onFinished(); return }
        current = s
        mutable.update { it.copy(busy = true, recording = false, cancelling = false, preview = "", result = "", audioLevel = 0f,
            recordingStartedAtMillis = s.monotonicStarted, recordingElapsedMillis = 0L, status = "Запуск микрофона…") }
        val capture = AudioCapture(onSamples = { buffer, count ->
            val remaining = (9_600_000L - s.spool.sampleCount).coerceAtLeast(0).toInt()
            val n = minOf(count, remaining)
            if (n > 0) {
                var energy = 0.0
                for (i in 0 until n) { val value = buffer[i].toDouble() / 32768.0; energy += value * value }
                // Log-scale meter for display only; never filters or normalizes the ASR audio.
                val rms = kotlin.math.sqrt(energy / n)
                val level = ((20 * kotlin.math.log10(rms.coerceAtLeast(0.00001)) + 60) / 60).toFloat().coerceIn(0f, 1f)
                mutable.update { if (current === s && s.recording) it.copy(audioLevel = level) else it }
                s.spool.append(buffer, n)
                s.segmenter.accept(buffer, n).forEach { check(s.queue.trySend(it).isSuccess) }
            }
            if (n < count || s.spool.sampleCount >= 9_600_000L) scope.launch { stop("limit") }
        }, onEnd = { failure ->
            if (failure != null) { s.reason = "interrupted"; scope.launch { message("Запись прервана. Сохраняю распознанную часть.") } }
            s.recording = false
            s.segmenter.finish().forEach { s.queue.trySend(it) }
            s.queue.close(); s.captureEnded.complete(Unit)
            mutable.update { if (current === s) it.copy(recording = false, audioLevel = 0f, recordingElapsedMillis = s.stopElapsed(), status = "Завершаю распознавание…") else it }
        })
        s.capture = capture
        try {
            capture.start()
            mutable.update { it.copy(recording = true, status = "Слушаю · всё остаётся на устройстве") }
        } catch (_: Exception) {
            gate.finish(token); current = null; s.spool.delete()
            mutable.update { it.copy(busy = false, recording = false, audioLevel = 0f, recordingStartedAtMillis = 0L, recordingElapsedMillis = 0L, status = "Микрофон недоступен. Проверьте разрешение и другие приложения.") }
            onFinished(); return
        }
        scope.launch { process(s, onFinished) }
    }
    fun stop(reason: String = "user") {
        val s = current ?: return
        if (reason !in listOf("user", "limit")) s.reason = reason
        if (!s.recording) return
        s.reason = reason; s.recording = false; gate.stop(s.token); s.capture?.stop()
        mutable.update { it.copy(recording = false, audioLevel = 0f, recordingElapsedMillis = s.stopElapsed(), status = "Завершаю распознавание…") }
    }
    fun cancel() {
        val s = current ?: return
        s.cancelled.set(true); gate.cancel(s.token); s.capture?.stop()
        // Native cancel only sets an atomic flag; actual close stays on serialized worker.
        engine?.cancel()
        mutable.update { it.copy(recording = false, cancelling = true, audioLevel = 0f, recordingElapsedMillis = s.stopElapsed(), preview = "", status = "Отменяю…") }
    }
    private suspend fun process(s: Session, onFinished: () -> Unit) {
        val assembler = TranscriptAssembler()
        var processedEnd = 0L
        var failed = false
        fun entry(text: String) = HistoryEntry(s.id, s.started, System.currentTimeMillis(), s.spool.sampleCount * 1000 / 16000,
            text, s.spec.title, s.language, s.reason)
        try {
            engineMutex.withLock {
                val asr = engineFor(s.spec, s.language)
                withContext(nativeDispatcher) { asr.beginSession(s.language) }
                var lastPreview = 0L
                while (!s.cancelled.get()) {
                    val next = withTimeoutOrNull(2000) { s.queue.receiveCatching() }
                    if (next != null) {
                        val segment = next.getOrNull() ?: break
                        val samples = withContext(Dispatchers.IO) { s.spool.read(segment.start, segment.end) }
                        val start = System.nanoTime()
                        val text = withContext(nativeDispatcher) { asr.transcribe(samples, s.language) }
                        if (!gate.accepts(s.token)) break
                        assembler.append(text, segment.overlapSamples > 0); processedEnd = segment.end
                        withContext(Dispatchers.IO) { if (assembler.text.isNotBlank()) history.draft(entry(assembler.text)) }
                        val behind = s.spool.sampleCount - processedEnd
                        val slow = System.nanoTime() - start > samples.size.toLong() * 1_000_000_000 / 16000
                        mutable.update { it.copy(preview = assembler.text, status = if (!s.recording) "Завершаю распознавание…" else if (slow && behind > 32000) "Модель отстаёт · звук сохранён" else "Слушаю · локальное распознавание") }
                    } else if (asr.allowsSpeculativePreviews && s.recording && s.spool.sampleCount - processedEnd >= 8000 && System.currentTimeMillis() - lastPreview >= 2000) {
                        val end = s.spool.sampleCount
                        val samples = withContext(Dispatchers.IO) { s.spool.read(maxOf(processedEnd, end - 128000), end) }
                        // Skip silence previews; mandatory segments retain priority at next iteration.
                        if (samples.any { kotlin.math.abs(it) > 0.025f }) {
                            val text = withContext(nativeDispatcher) { asr.transcribe(samples, s.language) }
                            if (gate.accepts(s.token)) mutable.update { it.copy(preview = listOf(assembler.text, text.trim()).filter(String::isNotBlank).joinToString(" ")) }
                        }
                        lastPreview = System.currentTimeMillis()
                    }
                }
            }
        } catch (_: Exception) {
            failed = true; s.reason = "interrupted"; s.capture?.stop()
            message("Распознавание прервано. Доступный текст сохранён в истории.")
        } finally {
            s.capture?.stop(); s.captureEnded.await()
            try {
                if (s.cancelled.get()) {
                    withContext(Dispatchers.IO) { history.discardDraft(s.id) }
                    message("Диктовка отменена")
                } else if (assembler.text.isNotBlank()) {
                    withContext(Dispatchers.IO) { history.save(entry(assembler.text)) }
                    if (s.cancelled.get()) {
                        withContext(Dispatchers.IO) { history.delete(s.id); history.discardDraft(s.id) }
                        message("Диктовка отменена")
                        return
                    }
                    val delivery = if (gate.accepts(s.token) && s.reason in listOf("user", "limit") && !locked())
                        LacanAccessibilityService.instance?.deliver(assembler.text) ?: "manual" else "manual"
                    withContext(Dispatchers.IO) { history.updateDelivery(s.id, delivery) }
                    if (s.cancelled.get()) {
                        withContext(Dispatchers.IO) { history.delete(s.id); history.discardDraft(s.id) }
                        message("Диктовка отменена")
                        return
                    }
                    mutable.update { it.copy(result = if (delivery == "inserted" || locked()) "" else assembler.text,
                        status = if (delivery == "inserted") "Текст вставлен" else if (locked()) "Текст сохранён в истории" else if (delivery == "uncertain") "Вставка не подтверждена — проверьте поле перед копированием" else "Текст сохранён · можно скопировать") }
                } else if (!failed) message("Речь не распознана")
            } catch (_: Exception) {
                if (s.cancelled.get()) {
                    withContext(Dispatchers.IO) { runCatching { history.delete(s.id); history.discardDraft(s.id) } }
                    message("Диктовка отменена")
                } else { message("Ошибка сохранения. Текст можно скопировать."); mutable.update { it.copy(result = if (locked()) "" else assembler.text) } }
            }
            finally {
                try { s.spool.delete() } catch (_: Exception) { message("Не удалось удалить временный файл; он будет очищен при следующем запуске") }
                finally {
                    gate.finish(s.token); if (current === s) current = null
                    mutable.update { it.copy(busy = false, recording = false, cancelling = false, audioLevel = 0f, preview = "") }; onFinished()
                }
            }
        }
    }
    private fun locked() = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked || !context.getSystemService(PowerManager::class.java).isInteractive
    fun dismissResult() { mutable.update { it.copy(result = "") } }
    fun copy(text: String) {
        if (locked() || text.isBlank()) return
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Lacan", text))
        message("Текст скопирован")
    }
    fun deleteHistory(id: String) { scope.launch(Dispatchers.IO) { history.delete(id) } }
    fun clearHistory() { scope.launch(Dispatchers.IO) { history.clear() } }
}
