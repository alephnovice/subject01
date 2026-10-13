package com.ballsort.auto.core

import java.util.PriorityQueue

/** ย้ายลูกบอลจากแก้ว from ไปแก้ว to (index ตามลำดับที่อ่านได้จากหน้าจอ) */
data class Move(val from: Int, val to: Int)

/**
 * Solver สำหรับ Ball Sort ทั่วไป — รองรับ
 *  - จำนวนแก้วเท่าไหร่ก็ได้ (รวมแก้วว่าง)
 *  - ความจุแก้วแต่ละใบไม่เท่ากันได้
 *  - จำนวนลูกต่อสีไม่ตายตัว (3/4/5/…)
 *  - โหมดย้ายทีละลูก หรือ ย้ายทั้งแถวสีเดียวกันบนสุด
 * เป้าหมาย: ทุกแก้วมีสีเดียว และแต่ละสีอยู่แค่แก้วเดียว
 */
object Solver {

    private class Node(val t: Array<String>, val g: Int, val parent: Node?, val mv: Move?)

    private fun enc(l: List<Int>) = String(CharArray(l.size) { (l[it] + 48).toChar() })

    fun isGoal(tubes: List<List<Int>>): Boolean {
        val seen = HashSet<Int>()
        for (t in tubes) {
            if (t.isEmpty()) continue
            val c = t[0]
            if (t.any { it != c }) return false
            if (!seen.add(c)) return false
        }
        return true
    }

    /** ย้ายตามกติกา แล้วคืนสถานะใหม่ (ใช้ทั้งใน solver และตรวจผลหลังแตะ) */
    fun apply(tubes: List<List<Int>>, caps: IntArray, mv: Move, runMode: Boolean): List<List<Int>> {
        val res = tubes.map { it.toMutableList() }
        val src = res[mv.from]
        val dst = res[mv.to]
        if (src.isEmpty()) return res
        val c = src.last()
        var n = 0
        while (n < src.size && src[src.size - 1 - n] == c) n++
        val k = if (runMode) minOf(n, caps[mv.to] - dst.size) else 1
        repeat(k) { dst.add(src.removeAt(src.size - 1)) }
        return res
    }

    fun solve(
        tubes: List<List<Int>>, caps: IntArray, runMode: Boolean,
        maxNodes: Int = 1_200_000, timeMs: Long = 10_000, weight: Double = 2.0
    ): List<Move>? {
        val n = tubes.size
        val total = HashMap<Int, Int>()
        tubes.forEach { t -> t.forEach { total[it] = (total[it] ?: 0) + 1 } }
        val start = Array(n) { enc(tubes[it]) }
        if (isGoal(tubes)) return emptyList()

        fun key(t: Array<String>): String {
            val parts = Array(n) { "${caps[it]}:${t[it]}" }
            parts.sort()
            return parts.joinToString("|")
        }

        fun mono(s: String) = s.isNotEmpty() && s.all { it == s[0] }

        fun h(t: Array<String>): Int {
            var v = 0
            for (s in t) {
                if (s.isEmpty()) continue
                var r = 1
                while (r < s.length && s[r] == s[0]) r++
                v += s.length - r
                if (r == s.length && (total[s[0] - '0'] ?: 0) > s.length) v += 1
            }
            return v
        }

        fun goal(t: Array<String>): Boolean {
            val seen = HashSet<Char>()
            for (s in t) {
                if (s.isEmpty()) continue
                if (!mono(s) || !seen.add(s[0])) return false
            }
            return true
        }

        val pq = PriorityQueue<Pair<Double, Node>>(compareBy({ it.first }, { -it.second.g }))
        val best = HashMap<String, Int>()
        val s0 = Node(start, 0, null, null)
        pq.add(Pair(weight * h(start), s0))
        best[key(start)] = 0
        val deadline = System.currentTimeMillis() + timeMs
        var expanded = 0

        while (pq.isNotEmpty()) {
            val node = pq.poll().second
            val t = node.t
            if (goal(t)) {
                val out = ArrayList<Move>()
                var cur: Node? = node
                while (cur?.mv != null) { out.add(cur.mv!!); cur = cur.parent }
                out.reverse()
                return out
            }
            if (++expanded > maxNodes || (expanded and 1023 == 0 && System.currentTimeMillis() > deadline)) return null
            if (best[key(t)]?.let { it < node.g } == true) continue

            for (i in 0 until n) {
                val si = t[i]
                if (si.isEmpty()) continue
                val c = si.last()
                val iMono = mono(si)
                // แก้วที่เสร็จแล้ว (สีเดียว และครบทุกลูกของสีนั้น) ไม่ต้องแตะ
                if (iMono && si.length == (total[c - '0'] ?: 0)) continue
                var run = 0
                while (run < si.length && si[si.length - 1 - run] == c) run++
                for (j in 0 until n) {
                    if (i == j) continue
                    val sj = t[j]
                    if (sj.length >= caps[j]) continue
                    if (sj.isEmpty()) { if (iMono) continue }
                    else if (sj.last() != c) continue
                    val k = if (runMode) minOf(run, caps[j] - sj.length) else 1
                    val nt = t.copyOf()
                    nt[i] = si.substring(0, si.length - k)
                    nt[j] = sj + si.substring(si.length - k)
                    val g = node.g + 1
                    val kk = key(nt)
                    val old = best[kk]
                    if (old != null && old <= g) continue
                    best[kk] = g
                    pq.add(Pair(g + weight * h(nt), Node(nt, g, node, Move(i, j))))
                }
            }
        }
        return null
    }
}
