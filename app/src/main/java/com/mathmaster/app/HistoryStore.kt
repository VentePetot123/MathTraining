package com.mathmaster.app

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Append-only binary log of finished sessions, one file per category.
 * Fixed 16-byte records: at(8) · avgMs(4) · accuracyPct(2) · reserved(2).
 * Record i lives at byte i*16, so any slice is read without parsing the rest.
 * Nothing is ever deleted except by an explicit Reset. (The reserved field is
 * there so a future record layout can be flagged without migrating old data.)
 */
class HistoryStore(filesDir: File) {
    companion object { const val REC = 16 }

    private val root = File(filesDir, "history").apply { mkdirs() }
    private val counts = ConcurrentHashMap<String, Int>()
    private fun file(cat: String) = File(root, "$cat.bin")

    /** Lock-free: safe on the UI thread even while a save is syncing to disk. */
    fun count(cat: String): Int =
        counts[cat] ?: (file(cat).length() / REC).toInt().also { counts.putIfAbsent(cat, it) }

    private fun encode(list: List<SessionPoint>): ByteArray {
        val buf = ByteBuffer.allocate(list.size * REC)
        for (p in list) {
            buf.putLong(p.at)
            buf.putInt(p.avgMs)
            buf.putShort(p.accuracyPct.coerceIn(0, 100).toShort())
            buf.putShort(0.toShort())
        }
        return buf.array()
    }

    @Synchronized
    fun append(cat: String, list: List<SessionPoint>) {
        if (list.isEmpty()) return
        val bytes = encode(list)
        RandomAccessFile(file(cat), "rw").use { f ->
            val whole = f.length() / REC * REC          // heal a torn last record
            if (whole != f.length()) f.setLength(whole)
            f.seek(whole)
            f.write(bytes)
            f.fd.sync()
            counts[cat] = ((whole + bytes.size) / REC).toInt()
        }
    }

    @Synchronized
    fun read(cat: String, from: Int, to: Int): List<SessionPoint> {
        val f = file(cat)
        if (!f.exists()) return emptyList()
        val total = (f.length() / REC).toInt()
        val a = from.coerceIn(0, total)
        val b = to.coerceIn(a, total)
        if (a == b) return emptyList()
        val bytes = ByteArray((b - a) * REC)
        RandomAccessFile(f, "r").use { it.seek(a.toLong() * REC); it.readFully(bytes) }
        val buf = ByteBuffer.wrap(bytes)
        return List(b - a) {
            val at = buf.getLong()
            val ms = buf.getInt()
            val acc = buf.getShort().toInt()
            buf.getShort()
            SessionPoint(acc, ms, at)
        }
    }

    /**
     * Puts old (pre-log) sessions in FRONT of whatever the log already holds.
     * Atomic (temp file + rename) and idempotent (skips if already imported),
     * so a crash or retry can neither lose nor duplicate sessions.
     */
    @Synchronized
    fun importLegacy(cat: String, legacy: List<SessionPoint>) {
        if (legacy.isEmpty()) return
        if (count(cat) >= legacy.size && read(cat, 0, legacy.size) == legacy) return
        val existing = if (file(cat).exists()) file(cat).readBytes() else ByteArray(0)
        val keep = existing.copyOf(existing.size / REC * REC)
        val head = encode(legacy)
        val tmp = File(root, "$cat.tmp")
        FileOutputStream(tmp).use { it.write(head); it.write(keep); it.fd.sync() }
        if (!tmp.renameTo(file(cat))) throw IOException("could not replace history log")
        counts[cat] = (head.size + keep.size) / REC
    }

    @Synchronized
    fun clear(cat: String) {
        file(cat).delete()
        File(root, "$cat.tmp").delete()
        counts[cat] = 0
    }
}

/**
 * Lightweight handle the UI uses to show history: total count, the newest sessions
 * (so the first frame needs no disk access) and a loader for any other slice.
 * Equality ignores the loader, so unchanged history never triggers recomposition.
 */
class HistoryRef(
    val catId: String,
    val total: Int,
    val tail: List<SessionPoint>,
    private val loader: (Int, Int) -> List<SessionPoint>
) {
    private val lastAt = tail.lastOrNull()?.at ?: 0L
    val tailFrom: Int get() = total - tail.size
    fun read(from: Int, to: Int): List<SessionPoint> = loader(from, to)

    override fun equals(other: Any?) =
        other is HistoryRef && other.catId == catId && other.total == total && other.lastAt == lastAt
    override fun hashCode() = (31 * catId.hashCode() + total) * 31 + lastAt.hashCode()

    companion object {
        val EMPTY = HistoryRef("", 0, emptyList()) { _, _ -> emptyList() }
    }
}