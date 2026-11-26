package ly.anoma.hex.typing

import java.io.File
import java.util.zip.GZIPInputStream
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Keyboard › Fix earlier words with and without Labs › Keyboard › Neural autocorrect, on the English gate's held-out
 * sentences: each word the synthetic taps typed wrong, gone back to with the rest of the sentence right, and how often
 * the strip offers the word meant (first, or among its three); and for words typed right, how often it offers anything.
 * Runs only with HEX_BENCH_EARLIER set (the model and its library under android/.dev/lm). */
class EarlierWordsBench {
    private val grid = KeyGrid(buildMap {
        for ((row, letters) in listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").withIndex())
            for ((i, c) in letters.withIndex()) put(c, KeyPoint(201 + (i - (letters.length - 1) / 2.0) * 39.5, 66.5 + 56 * row))
    }, 39.5, 56.0)

    private class Case(val before: String, val typed: String, val meant: String, val next: String?, val after: String)

    @Test fun bench() {
        assumeTrue(System.getenv("HEX_BENCH_EARLIER") != null)
        System.setProperty("hex.typing.lib", File("../.dev/lm/host/build/libhextyping.so").absolutePath)
        val neural = NeuralModel.open(File("../.dev/lm/dl/SmolLM2-135M.Q8_0.gguf").absolutePath, 4) ?: error("model didn't load")
        val decoder = SpatialDecoder(TypingModel.file(File("src/main/assets/typing/en.hextm"), "en"))
        System.getenv("HEX_BENCH_BEAM")?.let { decoder.revisitBeam = it.toInt() }
        val word = TypingWord(decoder, true)
        val wrong = ArrayList<Case>(); val right = ArrayList<Case>()
        val rows = GZIPInputStream(File("../benchmark/typing/held-out/en.tsv.gz").inputStream()).bufferedReader().readLines()
        for ((n, line) in rows.withIndex()) {
            val f = line.split('\t')
            val meant = f[2].trim().split(' '); val typed = f[3].trim().split(' ')
            if (meant.size != typed.size) continue
            for (i in meant.indices) {
                val m = meant[i]; val t = typed[i]
                if (m.length < 2 || !m.all { it.isLetter() } || !t.all { it.isLetter() } || t.isEmpty()) continue
                val case = Case(meant.take(i).joinToString(" ") { it } + if (i > 0) " " else "", t, m,
                    meant.getOrNull(i + 1)?.takeIf { w -> w.all { it.isLetter() || it == '\'' } },
                    if (i + 1 < meant.size) " " + meant.drop(i + 1).joinToString(" ") else "")
                if (!t.equals(m, ignoreCase = true)) wrong += case else if ((n + i) % 7 == 0) right += case
            }
        }
        val weights = (System.getenv("HEX_BENCH_WEIGHTS") ?: "1.5").split(',').map { it.toDouble() }
        val margins = (System.getenv("HEX_BENCH_MARGINS") ?: "10").split(',').map { it.toDouble() }
        fun score(cases: List<Case>, label: String) {
            var pairsFirst = 0; var pairsAny = 0; var pairsOffers = 0
            val first = HashMap<Pair<Double, Double>, Int>(); val any = HashMap<Pair<Double, Double>, Int>(); val offers = HashMap<Pair<Double, Double>, Int>()
            val out = File("build/earlier-bench-$label.txt").printWriter()
            var nanos = 0L
            for (c in cases) {
                val t0 = System.nanoTime()
                val pairs = word.revisit(c.typed, c.before, c.next, grid)
                val pool = word.revisitPool(c.typed, c.before, c.next, grid)
                nanos += (System.nanoTime() - t0) / 2
                val asked = TypingWord.revisitWords(pool, c.after)
                val costs = if (pool.any { !it.stands }) neural.costs(c.before, asked) else null
                if (pairs.firstOrNull()?.equals(c.meant, true) == true) pairsFirst++
                if (pairs.any { it.equals(c.meant, true) }) pairsAny++
                if (pairs.isNotEmpty()) pairsOffers++
                var shown = ""
                for (wt in weights) for (mg in margins) {
                    val order = costs?.let { TypingWord.revisitOrder(pool, it, wt, mg) }.orEmpty().take(TypingWord.REVISIT_WORDS)
                    val k = wt to mg
                    if (order.firstOrNull()?.equals(c.meant, true) == true) first.merge(k, 1, Int::plus)
                    if (order.any { it.equals(c.meant, true) }) any.merge(k, 1, Int::plus)
                    if (order.isNotEmpty()) offers.merge(k, 1, Int::plus)
                    if (wt == weights.first() && mg == margins.first()) shown = order.joinToString(",")
                }
                out.println("${c.before}[${c.typed}→${c.meant}]${c.after}\tpairs=${pairs.joinToString(",")}\tmodel=$shown")
            }
            out.close()
            fun pct(n: Int) = "%5.1f%%".format(100.0 * n / cases.size)
            println("$label ${cases.size} cases, beam ${decoder.revisitBeam}, ${"%.1f".format(nanos / 1e6 / cases.size)} ms a word")
            println("  pairs                 first ${pct(pairsFirst)}  in strip ${pct(pairsAny)}  any offer ${pct(pairsOffers)}")
            for (wt in weights) for (mg in margins) { val k = wt to mg
                println("  model w=$wt margin=$mg  first ${pct(first[k] ?: 0)}  in strip ${pct(any[k] ?: 0)}  any offer ${pct(offers[k] ?: 0)}") }
        }
        score(wrong, "wrong")
        score(right, "right")
    }
}
