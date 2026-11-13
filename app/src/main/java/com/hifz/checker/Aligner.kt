package com.hifz.checker

/** مطابقة تلاوتك مع نص المصحف: كلمة بكلمة وحركة بحركة (بدون أحكام التجويد). */
object Aligner {
    const val LOCAL_OK = 0.55   // كلما قلّ الرقم صار التطبيق أشد
    const val GLOBAL_OK = 0.45
    private const val INS = 0.8
    private const val DEL = 0.8
    private const val HARAKA = 0.3

    private val VOWELS = setOf('\u064B', '\u064C', '\u064D', '\u064E', '\u064F', '\u0650', '\u0651')
    private val DROP = Regex("[\u0610-\u061A\u0653-\u065F\u06D6-\u06E0\u06E2-\u06ED\u08D3-\u08FF\u06DD]")
    private val TOKEN = Regex("[\u0621-\u065F\u0670\u0671\u06D6-\u06ED]+")

    private fun mapped(c: Char): Char? = when (c) {
        '\u0623', '\u0625', '\u0622', '\u0671' -> '\u0627'
        '\u0649', '\u0626' -> '\u064A'
        '\u0624' -> '\u0648'
        '\u0621', '\u0652', '\u0640' -> null
        else -> c
    }

    private fun letters(x: String): String {
        val sb = StringBuilder()
        for (ch in x) {
            val c = mapped(ch) ?: continue
            if (c !in VOWELS && c in '\u0621'..'\u064A') sb.append(c)
        }
        return sb.toString()
    }

    private fun marksOf(x: String): List<String> {
        val out = ArrayList<String>()
        var cur = ArrayList<Char>()
        for (ch in x) {
            val c = mapped(ch) ?: continue
            if (c in VOWELS) cur.add(c)
            else if (c in '\u0621'..'\u064A') {
                if (cur.isNotEmpty()) out.add(String(cur.sorted().toCharArray()))
                cur = ArrayList()
            }
        }
        if (cur.isNotEmpty()) out.add(String(cur.sorted().toCharArray()))
        return out
    }

    class W(val raw: String, var ref: String = "", var end: Boolean = false) {
        val strict: String
        val alt: String
        val marks: List<String>
        val set: Set<String>

        init {
            var w = raw.replace('\u06E1', '\u0652')
            w = DROP.replace(w, "")
            val st = w.replace('\u0670', '\u0627')
            val al = w.replace("\u0670", "")
            strict = letters(st)
            alt = letters(al)
            marks = marksOf(al)
            set = setOf(strict, alt)
        }
    }

    class Err(val type: String, val expected: String, val got: String, val ref: String)
    class Result(val status: String, val pointer: Int, val errors: List<Err>)
    private class Op(val kind: Int, val i: Int, val j: Int) // 0 مطابقة/استبدال، 1 زائدة، 2 ناقصة
    private class Res(val avg: Double, val ws: Int, val j0: Int, val jend: Int, val ops: List<Op>, val pRel: Int?)

    private val BISM = "بِسْمِ ٱللَّهِ ٱلرَّحْمَـٰنِ ٱلرَّحِيمِ".split(" ").map { W(it) }

    private fun isBism(ws: List<W>): Boolean {
        if (ws.size < 4) return false
        for (i in 0 until 4) if (ws[i].set.none { it in BISM[i].set }) return false
        return true
    }

    /** ayahs: (رقم السورة، رقم الآية، النص) */
    fun buildWords(ayahs: List<Triple<Int, Int, String>>): List<W> {
        val out = ArrayList<W>()
        for ((sn, an, text) in ayahs) {
            var ws = text.split(" ").filter { it.isNotEmpty() }.map { W(it) }.filter { it.strict.isNotEmpty() }
            if (an == 1 && sn != 1 && sn != 9 && isBism(ws)) ws = ws.drop(4)
            for ((k, w) in ws.withIndex()) {
                w.ref = "$sn:$an"
                w.end = (k == ws.size - 1)
                out.add(w)
            }
        }
        return out
    }

    private fun tokenize(text: String): List<W> =
        TOKEN.findAll(text).map { W(it.value) }.filter { it.strict.isNotEmpty() }.toList()

    private fun ndist(a: String, b: String): Double {
        if (a == b) return 0.0
        val la = a.length
        val lb = b.length
        if (la == 0 || lb == 0) return 1.0
        var prev = IntArray(lb + 1) { it }
        for (i in 1..la) {
            val cur = IntArray(lb + 1)
            cur[0] = i
            for (j in 1..lb) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[lb].toDouble() / maxOf(la, lb)
    }

    private fun cost(h: W, e: W, diacOn: Boolean): Double {
        if (h.set.any { it in e.set }) {
            return if (diacOn && !e.end && h.marks != e.marks) HARAKA else 0.0
        }
        val d = minOf(ndist(h.strict, e.strict), ndist(h.alt, e.alt), ndist(h.strict, e.alt))
        return 0.3 + 0.7 * d
    }

    private fun align(H: List<W>, E: List<W>, ws: Int, we: Int, pRel: Int?, diacOn: Boolean): Res {
        val m = we - ws
        val n = H.size
        val dp = Array(n + 1) { DoubleArray(m + 1) }
        val bt = Array(n + 1) { IntArray(m + 1) }
        for (j in 0..m) {
            dp[0][j] = when {
                pRel == null -> 0.0
                j >= pRel -> DEL * (j - pRel)
                else -> 0.05 * (pRel - j)
            }
            bt[0][j] = 3
        }
        for (i in 1..n) {
            dp[i][0] = dp[i - 1][0] + INS
            bt[i][0] = 1
            val hi = H[i - 1]
            for (j in 1..m) {
                val s = dp[i - 1][j - 1] + cost(hi, E[ws + j - 1], diacOn)
                val a = dp[i - 1][j] + INS
                val d = dp[i][j - 1] + DEL
                if (s <= a && s <= d) { dp[i][j] = s; bt[i][j] = 0 }
                else if (a <= d) { dp[i][j] = a; bt[i][j] = 1 }
                else { dp[i][j] = d; bt[i][j] = 2 }
            }
        }
        var jend = 0
        for (j in 1..m) if (dp[n][j] < dp[n][jend]) jend = j
        val total = dp[n][jend]
        var i = n
        var j = jend
        val ops = ArrayList<Op>()
        while (i > 0) {
            when (bt[i][j]) {
                0 -> { ops.add(Op(0, i - 1, j - 1)); i--; j-- }
                1 -> { ops.add(Op(1, i - 1, -1)); i-- }
                else -> { ops.add(Op(2, -1, j - 1)); j-- }
            }
        }
        ops.reverse()
        return Res(total / maxOf(1, n), ws, j, jend, ops, pRel)
    }

    private fun errorsOf(r: Res, H: List<W>, E: List<W>, diacOn: Boolean): List<Err> {
        val errs = ArrayList<Err>()
        val ws = r.ws
        if (r.pRel != null && r.j0 > r.pRel) {
            for (k in r.pRel until r.j0) errs.add(Err("missing", E[ws + k].raw, "", E[ws + k].ref))
        }
        var lo = 0
        var hi = r.ops.size
        while (lo < hi && r.ops[lo].kind == 1) lo++
        while (hi > lo && r.ops[hi - 1].kind == 1) hi--
        for (idx in lo until hi) {
            val op = r.ops[idx]
            when (op.kind) {
                0 -> {
                    val h = H[op.i]
                    val e = E[ws + op.j]
                    if (h.set.any { it in e.set }) {
                        if (diacOn && !e.end && h.marks != e.marks)
                            errs.add(Err("harakat", e.raw, h.raw, e.ref))
                    } else {
                        val dist = minOf(ndist(h.strict, e.strict), ndist(h.alt, e.alt))
                        errs.add(Err(if (dist <= 0.34) "letter" else "word", e.raw, h.raw, e.ref))
                    }
                }
                2 -> errs.add(Err("missing", E[ws + op.j].raw, "", E[ws + op.j].ref))
                else -> errs.add(Err("extra", "", H[op.i].raw, ""))
            }
        }
        return errs
    }

    fun evaluate(E: List<W>, hyp: String, pointer: Int): Result {
        val H = tokenize(hyp)
        if (H.size < 2) return Result("short", pointer, emptyList())
        val variants = ArrayList<List<W>>()
        variants.add(H)
        if (H.size > 4 && isBism(H)) variants.add(H.drop(4))
        val diacOn = H.count { it.marks.isNotEmpty() } >= 0.5 * H.size

        fun bestOf(ws: Int, we: Int, pRel: Int?): Pair<List<W>, Res> {
            var best: Pair<List<W>, Res>? = null
            for (v in variants) {
                val r = align(v, E, ws, we, pRel, diacOn)
                if (best == null || r.avg < best.second.avg) best = Pair(v, r)
            }
            return best!!
        }

        if (pointer >= 0) {
            val p = minOf(pointer, E.size)
            val ws = maxOf(0, p - 6)
            val we = minOf(E.size, p + (H.size * 1.6).toInt() + 12)
            val (v, r) = bestOf(ws, we, p - ws)
            if (r.avg <= LOCAL_OK) {
                val np = ws + r.jend
                return Result(if (np >= E.size) "finished" else "matched", np, errorsOf(r, v, E, diacOn))
            }
        }
        val (v, g) = bestOf(0, E.size, null)
        if (g.avg <= GLOBAL_OK) {
            val errs = if (pointer < 0) errorsOf(g, v, E, diacOn) else emptyList()
            return Result(if (pointer < 0) "located" else "relocated", g.jend, errs)
        }
        return Result("unclear", pointer, emptyList())
    }
}
