package com.ballsort.auto.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class Tube(
    val x0: Int, val y0: Int, val x1: Int, val y1: Int,
    val capacity: Int, val balls: List<Int>
) {
    val cx get() = (x0 + x1) / 2f
    val cy get() = (y0 + y1) / 2f
}

class Board(val tubes: List<Tube>, val ballDiameter: Float) {
    val balls: List<List<Int>> get() = tubes.map { it.balls }
    val caps: IntArray get() = IntArray(tubes.size) { tubes[it].capacity }
    fun describe(): String = tubes.mapIndexed { i, t -> "#${i + 1} cap=${t.capacity} ${t.balls}" }.joinToString("\n")
}

/**
 * อ่านกระดานจากภาพหน้าจอ (ARGB int array) — ไม่ผูกกับ Android เพื่อให้เทสต์บน JVM ได้
 *  1) หาแก้ว: ขอบสีขาวบางๆ รูปทรงสูง (connected component)
 *  2) วัดขนาดลูกบอลจากความกว้างจริงบนภาพ
 *  3) แบ่งช่องในแก้วจากก้นแก้ว ขึ้นไปทีละช่อง อ่านสีกลางลูก แล้วจัดกลุ่มสีด้วย palette
 */
object Vision {
    private fun r(p: Int) = (p shr 16) and 255
    private fun g(p: Int) = (p shr 8) and 255
    private fun b(p: Int) = p and 255

    private fun isWhite(p: Int) = min(r(p), min(g(p), b(p))) >= 200
    private fun isDark(p: Int) = max(r(p), max(g(p), b(p))) < 70
    private fun isBall(p: Int): Boolean {
        // ลูกบอล = ไม่ใช่พื้นหลังสีเข้ม (รวมสีเทา/ขาวด้วย)
        return max(r(p), max(g(p), b(p))) >= 75
    }

    private class Box(var x0: Int, var y0: Int, var x1: Int, var y1: Int)

    private fun findTubes(px: IntArray, w: Int, h: Int): List<Box> {
        val white = BooleanArray(w * h) { isWhite(px[it]) }
        val queue = IntArray(w * h)
        val boxes = ArrayList<Box>()
        for (s in 0 until w * h) {
            if (!white[s]) continue
            var qh = 0; var qt = 0
            queue[qt++] = s; white[s] = false
            val bx = Box(s % w, s / w, s % w, s / w)
            while (qh < qt) {
                val c = queue[qh++]
                val cx = c % w; val cy = c / w
                if (cx < bx.x0) bx.x0 = cx; if (cx > bx.x1) bx.x1 = cx
                if (cy < bx.y0) bx.y0 = cy; if (cy > bx.y1) bx.y1 = cy
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = cx + dx; val ny = cy + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val ni = ny * w + nx
                    if (white[ni]) { white[ni] = false; queue[qt++] = ni }
                }
            }
            val bw = bx.x1 - bx.x0 + 1; val bh = bx.y1 - bx.y0 + 1
            val ratio = bh.toFloat() / bw
            if (bw >= 0.035f * w && bw <= 0.25f * w && bh >= 0.07f * h && bh <= 0.45f * h && ratio in 1.6f..9f) boxes.add(bx)
        }
        // ตัดกล่องที่ซ้อนอยู่ในกล่องอื่น
        return boxes.filter { a -> boxes.none { o -> o !== a && o.x0 <= a.x0 && o.y0 <= a.y0 && o.x1 >= a.x1 && o.y1 >= a.y1 } }
    }

    private fun median(v: IntArray): Int { v.sort(); return v[v.size / 2] }

    private fun sampleColor(px: IntArray, w: Int, h: Int, cx: Int, cy: Int, rad: Int): Int {
        val rs = ArrayList<Int>(); val gs = ArrayList<Int>(); val bs = ArrayList<Int>()
        for (y in cy - rad..cy + rad) for (x in cx - rad..cx + rad) {
            if (x < 0 || y < 0 || x >= w || y >= h) continue
            val p = px[y * w + x]; rs.add(r(p)); gs.add(g(p)); bs.add(b(p))
        }
        if (rs.isEmpty()) return 0
        return (median(rs.toIntArray()) shl 16) or (median(gs.toIntArray()) shl 8) or median(bs.toIntArray())
    }

    private fun colorId(c: Int, palette: MutableList<IntArray>): Int {
        val v = intArrayOf(r(c), g(c), b(c))
        var best = -1; var bd = Int.MAX_VALUE
        for ((i, p) in palette.withIndex()) {
            val d = (p[0] - v[0]) * (p[0] - v[0]) + (p[1] - v[1]) * (p[1] - v[1]) + (p[2] - v[2]) * (p[2] - v[2])
            if (d < bd) { bd = d; best = i }
        }
        if (best >= 0 && bd <= 60 * 60) return best
        palette.add(v)
        return palette.size - 1
    }

    /** palette ใช้ซ้ำข้ามการสแกน เพื่อให้ id สีเดิมคงที่ */
    fun read(px: IntArray, w: Int, h: Int, palette: MutableList<IntArray>): Board? {
        val boxes = findTubes(px, w, h)
        if (boxes.size < 2) return null

        // เรียงตามแถว แล้วซ้าย→ขวา
        val medH = boxes.map { it.y1 - it.y0 }.sorted()[boxes.size / 2]
        val sorted = boxes.sortedBy { it.y0 }
        val rows = ArrayList<MutableList<Box>>()
        for (bx in sorted) {
            val row = rows.lastOrNull()
            if (row != null && abs(bx.y0 - row[0].y0) < medH * 0.5f) row.add(bx) else rows.add(mutableListOf(bx))
        }
        val ordered = rows.flatMap { row -> row.sortedBy { it.x0 } }

        // วัดเส้นผ่านศูนย์กลางลูกบอลจากภาพจริง (สแกนแนวนอนผ่านกลางลูกล่างสุด)
        val widths = ArrayList<Int>()
        for (bx in ordered) {
            val tw = bx.x1 - bx.x0 + 1
            val cx = (bx.x0 + bx.x1) / 2
            val cy = (bx.y1 - 0.53f * tw).roundToInt()
            if (cy !in 0 until h || !isBall(px[cy * w + cx]) && !isBall(px[cy * w + cx + tw / 6])) continue
            var l = cx; var run = 0
            var x = cx
            while (x > bx.x0 + 2) { if (isDark(px[cy * w + x])) { run++; if (run >= 3) break } else { run = 0; l = x }; x-- }
            var rr = cx; run = 0; x = cx
            while (x < bx.x1 - 2) { if (isDark(px[cy * w + x])) { run++; if (run >= 3) break } else { run = 0; rr = x }; x++ }
            widths.add(rr - l + 1)
        }
        val tw0 = ordered.map { it.x1 - it.x0 + 1 }.sorted()[ordered.size / 2]
        val d = if (widths.isEmpty()) 0.86f * tw0 else widths.sorted()[widths.size / 2].toFloat()
        val pitch = d * 1.04f

        // เก็บสีทุกช่องก่อน แล้วค่อยแปลงเป็น id (palette เดิม + สีใหม่)
        val tubes = ArrayList<Tube>()
        for (bx in ordered) {
            val cx = (bx.x0 + bx.x1) / 2
            val th = bx.y1 - bx.y0 + 1
            var cap = max(2, (th / pitch - 0.02f).toInt())
            val bottomPad = 0.10f * pitch
            val balls = ArrayList<Int>()
            var k = 0
            while (k < cap + 2) {
                val cy = (bx.y1 - bottomPad - (k + 0.5f) * pitch + 0.12f * d).roundToInt()
                if (cy < bx.y0) break
                val c = sampleColor(px, w, h, cx, cy, max(2, (0.16f * d).toInt()))
                if (!isBall(c)) break
                balls.add(colorId(c, palette))
                k++
            }
            if (balls.size > cap) cap = balls.size
            tubes.add(Tube(bx.x0, bx.y0, bx.x1, bx.y1, cap, balls))
        }
        return Board(tubes, d)
    }
}
