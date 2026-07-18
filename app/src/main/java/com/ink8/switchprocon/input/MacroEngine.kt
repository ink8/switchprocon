package com.ink8.switchprocon.input

import com.ink8.switchprocon.protocol.ControllerState
import com.ink8.switchprocon.protocol.ControllerState.Button
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections

/**
 * Power features layered on top of raw input, modeled after the Manba One's back keys:
 *
 *  - **Macro slots M1–M4**: each slot records its own timed button sequence ("ultimate
 *    move") and replays it with one tap. Long-press a slot to record into it.
 *  - **Loop**: replay the played slot continuously until stopped.
 *  - **Turbo**: auto-fire on any held button, with adjustable speed.
 *
 * All live button input flows through [press] so an in-progress recording captures it.
 * Slots serialize to JSON so profiles can save/restore them.
 */
class MacroEngine(private val state: ControllerState) {

    data class Event(val offsetMs: Long, val button: Button, val pressed: Boolean)

    /** Recorded macro per slot, or null if empty. */
    val slots = arrayOfNulls<List<Event>>(SLOT_COUNT)

    @Volatile var recordingSlot = -1
        private set
    @Volatile var isPlaying = false
        private set
    @Volatile var loopEnabled = false
    @Volatile var turboIntervalMs: Long = 66 // ~15 presses/sec (on+off phases)

    private val recordBuffer = mutableListOf<Event>()
    private var recordStartMs = 0L
    private val turboButtons: MutableSet<Button> = Collections.synchronizedSet(mutableSetOf())
    private var playThread: Thread? = null
    private var turboThread: Thread? = null

    /** Apply a live button press, recording it if a slot recording is in progress. */
    fun press(button: Button, pressed: Boolean) {
        state.setButton(button, pressed)
        if (recordingSlot >= 0) {
            synchronized(recordBuffer) {
                recordBuffer.add(Event(System.currentTimeMillis() - recordStartMs, button, pressed))
            }
        }
    }

    /**
     * Start recording into [slot], or — if that slot is already recording — stop and save.
     * @return true if now recording, false if recording just stopped.
     */
    fun toggleRecording(slot: Int): Boolean {
        if (recordingSlot == slot) {
            synchronized(recordBuffer) {
                slots[slot] = if (recordBuffer.isEmpty()) null else recordBuffer.toList()
                recordBuffer.clear()
            }
            recordingSlot = -1
            return false
        }
        stopPlayback()
        synchronized(recordBuffer) { recordBuffer.clear() }
        recordStartMs = System.currentTimeMillis()
        recordingSlot = slot
        return true
    }

    fun hasMacro(slot: Int): Boolean = slots.getOrNull(slot) != null

    /** Fire a slot's macro once, or forever while [loopEnabled] is on. */
    fun playSlot(slot: Int) {
        val events = slots.getOrNull(slot) ?: return
        if (isPlaying) stopPlayback()
        isPlaying = true
        playThread = Thread {
            try {
                do {
                    var last = 0L
                    for (e in events) {
                        if (!isPlaying) break
                        val wait = e.offsetMs - last
                        if (wait > 0) Thread.sleep(wait)
                        last = e.offsetMs
                        state.setButton(e.button, e.pressed)
                    }
                } while (isPlaying && loopEnabled)
            } catch (_: InterruptedException) {
                // stopped
            } finally {
                isPlaying = false
            }
        }.also { it.isDaemon = true; it.start() }
    }

    fun stopPlayback() {
        isPlaying = false
        playThread?.interrupt()
        playThread = null
    }

    // ---- Turbo ----

    fun toggleTurbo(button: Button): Boolean {
        val nowOn = if (turboButtons.contains(button)) {
            turboButtons.remove(button); false
        } else {
            turboButtons.add(button); true
        }
        ensureTurboThread()
        return nowOn
    }

    fun isTurbo(button: Button): Boolean = turboButtons.contains(button)

    private fun ensureTurboThread() {
        if (turboThread != null) return
        turboThread = Thread {
            var on = false
            try {
                while (true) {
                    on = !on
                    val snapshot = synchronized(turboButtons) { turboButtons.toList() }
                    for (b in snapshot) state.setButton(b, on)
                    Thread.sleep(turboIntervalMs / 2)
                }
            } catch (_: InterruptedException) {
                // stopped
            }
        }.also { it.isDaemon = true; it.start() }
    }

    // ---- Profile serialization ----

    fun slotsToJson(): JSONArray {
        val arr = JSONArray()
        for (slot in slots) {
            val slotArr = JSONArray()
            slot?.forEach { e ->
                slotArr.put(
                    JSONObject().put("t", e.offsetMs).put("b", e.button.name).put("p", e.pressed)
                )
            }
            arr.put(slotArr)
        }
        return arr
    }

    fun loadSlotsFromJson(arr: JSONArray) {
        for (i in 0 until SLOT_COUNT) {
            val slotArr = arr.optJSONArray(i)
            slots[i] = if (slotArr == null || slotArr.length() == 0) null else {
                (0 until slotArr.length()).mapNotNull { j ->
                    val o = slotArr.getJSONObject(j)
                    val button = runCatching { Button.valueOf(o.getString("b")) }.getOrNull()
                    button?.let { Event(o.getLong("t"), it, o.getBoolean("p")) }
                }
            }
        }
    }

    fun shutdown() {
        stopPlayback()
        turboThread?.interrupt()
        turboThread = null
        turboButtons.clear()
    }

    companion object {
        const val SLOT_COUNT = 4
    }
}
