package jp.relative.tech.one_touch_controller.domain.ports

import jp.relative.tech.one_touch_controller.domain.recording.RecEvent

/** Port for recording, persisting and replaying mouse/keyboard actions. */
interface RecordingPort {
    val isRecording: Boolean
    val isPlaying: Boolean

    fun startRecording(progress: (Int) -> Unit, stopped: (List<RecEvent>) -> Unit)
    fun stopRecording(fromButton: Boolean = false)

    fun save(id: String, events: List<RecEvent>)
    fun load(fileName: String): List<RecEvent>
    fun delete(fileName: String)
    fun listRecordingIds(): Set<String>

    /** Plays a recording. Returns true if it reached the end, false if stopped early (F9). */
    fun play(events: List<RecEvent>): Boolean
}
