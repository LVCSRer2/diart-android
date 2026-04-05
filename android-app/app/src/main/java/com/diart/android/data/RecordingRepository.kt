package com.diart.android.data

import android.content.Context
import com.diart.android.audio.WavWriter
import com.diart.android.pipeline.SegmentEntry
import com.diart.android.pipeline.SpeakerTurn
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

object RecordingRepository {

    private const val DIR   = "recordings"
    private const val INDEX = "index.json"
    private const val EMB_DIM = 512

    private fun dir(context: Context)       = File(context.filesDir, DIR).also { it.mkdirs() }
    private fun indexFile(context: Context) = File(dir(context), INDEX)

    // ── 목록 불러오기 ────────────────────────────────────────────────────

    fun loadAll(context: Context): List<RecordingInfo> {
        val file = indexFile(context)
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                val id = obj.getString("id")
                val wavFile      = File(dir(context), "$id.wav")
                val turnsFile    = File(dir(context), "$id.json")
                val segmentsFile = File(dir(context), "$id.bin")
                if (!wavFile.exists()) return@mapNotNull null
                RecordingInfo(
                    id           = id,
                    createdAt    = obj.getLong("createdAt"),
                    durationSec  = obj.getDouble("durationSec").toFloat(),
                    speakerCount = obj.getInt("speakerCount"),
                    turnCount    = obj.getInt("turnCount"),
                    wavFile      = wavFile,
                    turnsFile    = turnsFile,
                    segmentsFile = if (segmentsFile.exists()) segmentsFile else null,
                )
            }.sortedByDescending { it.createdAt }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ── 저장 ─────────────────────────────────────────────────────────────

    fun save(
        context: Context,
        pcmData: ByteArray,
        turns: List<SpeakerTurn>,
        segments: List<SegmentEntry>,
        durationSec: Float,
        sampleRate: Int,
    ): RecordingInfo {
        val id           = UUID.randomUUID().toString()
        val d            = dir(context)
        val wavFile      = File(d, "$id.wav")
        val turnsFile    = File(d, "$id.json")
        val segmentsFile = if (segments.isNotEmpty()) File(d, "$id.bin") else null

        WavWriter.write(wavFile, pcmData, sampleRate)
        turnsFile.writeText(serializeTurns(turns))
        segmentsFile?.let { writeSegments(it, segments) }

        val speakerCount = turns.map { it.speakerId }.toSet().size
        val info = RecordingInfo(
            id           = id,
            createdAt    = System.currentTimeMillis(),
            durationSec  = durationSec,
            speakerCount = speakerCount,
            turnCount    = turns.size,
            wavFile      = wavFile,
            turnsFile    = turnsFile,
            segmentsFile = segmentsFile,
        )
        val existing = loadAll(context).toMutableList()
        existing.add(0, info)
        writeIndex(context, existing)
        return info
    }

    // ── 삭제 ─────────────────────────────────────────────────────────────

    fun delete(context: Context, id: String) {
        File(dir(context), "$id.wav").delete()
        File(dir(context), "$id.json").delete()
        File(dir(context), "$id.bin").delete()
        val updated = loadAll(context).filter { it.id != id }
        writeIndex(context, updated)
    }

    // ── Turn 직렬화 ───────────────────────────────────────────────────────

    fun loadTurns(turnsFile: File): List<SpeakerTurn> {
        if (!turnsFile.exists()) return emptyList()
        return try { deserializeTurns(turnsFile.readText()) } catch (e: Exception) { emptyList() }
    }

    fun serializeTurns(turns: List<SpeakerTurn>): String {
        val arr = JSONArray()
        for (t in turns) {
            arr.put(JSONObject().apply {
                put("speakerId", t.speakerId)
                put("startSec",  t.startSec.toDouble())
                put("endSec",    t.endSec.toDouble())
            })
        }
        return arr.toString()
    }

    private fun deserializeTurns(json: String): List<SpeakerTurn> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            arr.getJSONObject(i).let { obj ->
                SpeakerTurn(
                    speakerId = obj.getInt("speakerId"),
                    startSec  = obj.getDouble("startSec").toFloat(),
                    endSec    = obj.getDouble("endSec").toFloat(),
                )
            }
        }
    }

    // ── Segment 임베딩 바이너리 직렬화 ────────────────────────────────────
    // 포맷: [4B: N] × N × [4B: startSec][4B: endSec][EMB_DIM×4B: embedding]

    fun loadSegments(file: File): List<SegmentEntry> {
        if (!file.exists()) return emptyList()
        return try {
            val buf = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            val n   = buf.int
            List(n) {
                val start = buf.float
                val end   = buf.float
                val emb   = FloatArray(EMB_DIM) { buf.float }
                SegmentEntry(start, end, emb)
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun writeSegments(file: File, segments: List<SegmentEntry>) {
        val entryBytes = 4 + 4 + EMB_DIM * 4
        val buf = ByteBuffer.allocate(4 + segments.size * entryBytes)
            .order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(segments.size)
        for (seg in segments) {
            buf.putFloat(seg.startSec)
            buf.putFloat(seg.endSec)
            for (v in seg.embedding) buf.putFloat(v)
        }
        file.writeBytes(buf.array())
    }

    // ── 내부: index 쓰기 ──────────────────────────────────────────────────

    private fun writeIndex(context: Context, recordings: List<RecordingInfo>) {
        val arr = JSONArray()
        for (r in recordings) {
            arr.put(JSONObject().apply {
                put("id",           r.id)
                put("createdAt",    r.createdAt)
                put("durationSec",  r.durationSec.toDouble())
                put("speakerCount", r.speakerCount)
                put("turnCount",    r.turnCount)
            })
        }
        indexFile(context).writeText(arr.toString())
    }
}
