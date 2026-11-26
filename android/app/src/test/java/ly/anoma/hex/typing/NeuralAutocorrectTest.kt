package ly.anoma.hex.typing

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Labs › Keyboard › Neural autocorrect's part of the decoder, with a stand-in for the model. */
class NeuralAutocorrectTest {
    private val model = TypingModel.file(File("src/main/assets/typing/en.hextm"), "en")
    private val grid = KeyGrid(buildMap {
        for ((row, letters) in listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").withIndex())
            for ((i, c) in letters.withIndex()) put(c, KeyPoint(201 + (i - (letters.length - 1) / 2.0) * 39.5, 66.5 + 56 * row))
    }, 39.5, 56.0)

    /** A model that finds [likely] cheap and everything else dear, and remembers what it was asked. */
    private class Stand(val likely: Set<String>, val answers: Boolean = true) : SpatialDecoder.NeuralScorer {
        val asked = mutableListOf<List<String>>()
        override fun costs(context: String, words: List<String>): DoubleArray? {
            asked += words
            return if (answers) DoubleArray(words.size) { if (words[it].lowercase() in likely) 5.0 else 30.0 } else null
        }
    }

    private fun decide(d: SpatialDecoder, typed: String, context: String?) =
        d.decide(typed, typed.map { c -> grid.centres.getValue(c).let { d.score(grid, it.x, it.y) } }, -1, false, context)

    @Test fun theModelKeepsAWordThePairsWouldSplit() {
        val d = SpatialDecoder(model).also { it.strong = true }
        assertEquals("the word pairs split it", "tail scale", decide(d, "tailscale", null).replace?.lowercase())
        val stand = Stand(setOf("tailscale"))
        d.neural = stand
        assertNull("the model reads the spelling as likeliest: no fix, and no missed-space fallback either",
            decide(d, "tailscale", "My home server runs on").replace)
        assertTrue(stand.asked.first().first() == "tailscale")
        assertTrue("the spelling and at most 9 readings", stand.asked.first().size <= 10)
    }

    @Test fun theModelPicksAmongTheReadings() {
        val d = SpatialDecoder(model)
        d.neural = Stand(setOf("buy"))
        assertEquals("buy", decide(d, "biy", "I want to").replace)
        d.neural = Stand(setOf("bit"))
        assertEquals("bit", decide(d, "biy", "Just a little").replace)
    }

    @Test fun withoutAnAnswerTheWordPairsDecide() {
        val d = SpatialDecoder(model).also { it.strong = true }
        val pairs = decide(d, "tailscale", null).replace
        d.neural = Stand(setOf("tailscale"), answers = false)
        assertEquals(pairs, decide(d, "tailscale", "My home server runs on").replace)
        assertEquals("no context, no model", pairs, decide(d.also { it.neural = Stand(setOf("tailscale")) }, "tailscale", null).replace)
    }

    @Test fun aWordYouTaughtItKeepsItsPlace() {
        val d = SpatialDecoder(model).also { it.strong = true }
        assertEquals("the pairs correct it while it's unknown", "help", decide(d, "hepl", null).replace)
        d.personal = PersonalWords(model, listOf("hepl"), emptyMap())
        assertNull("learned, the pairs keep it", decide(d, "hepl", null).replace)
        val stand = Stand(setOf("help"))
        d.neural = stand
        assertNull("the model, which finds it unlikely, doesn't take it away", decide(d, "hepl", "Thanks for the").replace)
        assertTrue(stand.asked.isNotEmpty())
    }

    @Test fun aLetterWhereTheSpaceShouldBe() {
        val d = SpatialDecoder(model).also { it.strong = true }
        val pairs = decide(d, "thatshcrazy", null)
        assertTrue("the word pairs alone don't split on a letter: ${pairs.replace}", pairs.replace?.lowercase() != "that's crazy")
        d.neural = Stand(setOf("that's crazy"))
        assertEquals("with the model, the h is the space and thats is that's", "that's crazy", decide(d, "thatshcrazy", "Wow,").replace)
        d.neural = Stand(setOf("kubernetes"))
        assertNull("a word the model reads as likeliest keeps its letters", decide(d, "kubernetes", "We deploy it on").replace)
    }

    @Test fun aContractionInsideARunOfWords() {
        val d = SpatialDecoder(model).also { it.strong = true }
        d.neural = Stand(setOf("that's so crazy"))
        assertEquals("that's so crazy", decide(d, "thatssocrazy", "Wow,").replace)
    }

    @Test fun theModelRanksTheCompletions() {
        val w = TypingWord(SpatialDecoder(model), true)
        fun type(before: String, letters: String) {
            w.reset(before)
            for (c in letters) grid.centres.getValue(c).let { w.letter(c.toString(), it.x, it.y, grid) }
        }
        type("We were probably ", "go")
        val pairs = w.strip(autocorrect = false).map { it.text }
        assertTrue("the word pairs don't offer it yet: $pairs", "gonna" !in pairs)
        val stand = Stand(setOf("gonna"))
        w.decoder.neural = stand
        var answers = 0
        w.rankCompletions({ it() }) { answers++ }
        assertEquals(1, answers)
        assertTrue("the commonest go… words and gonna: ${stand.asked}", "gonna" in stand.asked.single() && stand.asked.single().size <= TypingWord.NEURAL_COMPLETIONS)
        assertEquals("the model's likeliest first, after the word as typed", "gonna",
            w.strip(autocorrect = false).first { it.kind != Suggestion.Kind.Typed }.text)
        w.rankCompletions({ it() }) { answers++ }
        assertEquals("asked once for the same letters", 1, answers)
        type("We were probably ", "gon")
        w.rankCompletions({ it() }) {}
        val full = w.strip()
        assertEquals("space would put in gone", "gone", full.first { it.kind == Suggestion.Kind.Fix }.text)
        assertTrue("and the full strip keeps the model's gonna beside it: $full", full.any { it.text == "gonna" })
        type("I can't ", "go")
        assertTrue("in another sentence the old answer doesn't hold", w.strip(autocorrect = false).map { it.text }.let { "gonna" !in it })
    }

    @Test fun theModelRanksTheNextWords() {
        val w = TypingWord(SpatialDecoder(model), true)
        val before = "I need to pick up my "
        w.reset(before)
        val pairs = w.predictions(before)
        val stand = Stand(setOf("kids"))
        w.decoder.neural = stand
        var answers = 0
        w.rankPredictions(before, { it() }) { answers++ }
        assertEquals(1, answers)
        val asked = stand.asked.single()
        assertTrue("the pairs' words and more, at most two batches: $asked", asked.containsAll(pairs) && asked.size <= TypingWord.NEURAL_COMPLETIONS)
        val other = TypingWord(SpatialDecoder(model).also { it.neural = Stand(setOf(asked.last())) }, true).also { it.reset(before) }
        other.rankPredictions(before, { it() }) {}
        assertEquals("the model's likeliest first", asked.last(), other.predictions(before).first().lowercase())
        w.rankPredictions(before, { it() }) { answers++ }
        assertEquals("asked once for the same text", 1, answers)
        assertEquals("another sentence keeps the pairs' order", w.predictions("Can you send me the ").also { w.reset("Can you send me the ") },
            TypingWord(SpatialDecoder(model), true).predictions("Can you send me the "))
        stand.asked.clear()
        w.rankPredictions("Hello. ", { it() }) { answers++ }
        assertTrue("a sentence's start isn't asked", stand.asked.isEmpty())
    }

    @Test fun theModelRanksAWordYouWentBackTo() {
        val w = TypingWord(SpatialDecoder(model), true)
        val pool = w.revisitPool("by", "I want to ", "a", grid)
        assertTrue("the word as it stands is read too: $pool", pool.first().stands && pool.first().text == "by")
        val asked = TypingWord.revisitWords(pool, " a car. Then home")
        assertEquals("each reading with the rest of its sentence", "by a car.", asked.first())
        val likes = asked.first { it.startsWith("bt ") || it.startsWith("bu") && it != "buy a car." }
        val order = TypingWord.revisitOrder(pool, Stand(setOf(likes)).costs("I want to", asked)!!, 1.5)!!
        assertEquals("the model's likeliest in its sentence first", likes.substringBefore(' '), order.first())
        assertEquals("nothing for a word the model finds right", emptyList<String>(),
            TypingWord.revisitOrder(pool, Stand(setOf("by a car.")).costs("I want to", asked)!!, 1.5))
        assertEquals(null, TypingWord.revisitOrder(pool, DoubleArray(1), 1.5))
        assertEquals("without the model, what the word pairs offer", w.revisit("by", "I want to ", "a", grid),
            pool.filter { it.offered && !it.stands }.take(TypingWord.REVISIT_WORDS).map { it.text })
    }

    /** A straight swipe through [word]'s key centres, a point every 4 px. */
    private fun swipe(word: String): Pair<DoubleArray, DoubleArray> {
        val keys = word.map { grid.centres.getValue(it) }.fold(listOf<KeyPoint>()) { a, k -> if (a.lastOrNull() == k) a else a + k }
        val xs = arrayListOf(keys[0].x); val ys = arrayListOf(keys[0].y)
        for (k in 1 until keys.size) {
            val a = keys[k - 1]; val b = keys[k]
            val n = maxOf(1, (Math.hypot(b.x - a.x, b.y - a.y) / 4).toInt())
            for (i in 1..n) { xs += a.x + (b.x - a.x) * i / n; ys += a.y + (b.y - a.y) * i / n }
        }
        return xs.toDoubleArray() to ys.toDoubleArray()
    }
    private fun SwipeReader.read(word: String, context: String?, cased: (String) -> String = { it }): List<String> {
        val (xs, ys) = swipe(word)
        return decode(xs, ys, null, xs.size, grid, null, 8, context, cased).candidates.map { it.text }
    }

    @Test fun theModelReranksASwipe() {
        val spatial = SpatialDecoder(model)
        val reader = SwipeReader(spatial).also { it.prepare(false) }
        val pairs = reader.read("for", "Thanks")
        assertTrue("the path has more than one reading: $pairs", pairs.size >= 2)
        val stand = Stand(setOf(pairs[1]))
        spatial.neural = stand
        assertEquals("the model's likelier word goes first", pairs[1], reader.read("for", "Thanks").first())
        reader.read("for", "Thanks") { it.uppercase() }
        assertEquals("it reads every reading as it would go in, and more than the strip shows",
            pairs.map { it.uppercase() }.distinct(), stand.asked.last().take(pairs.size))
        assertEquals(SwipeReader.NEURAL_READINGS, stand.asked.last().size)
        stand.asked.clear()
        assertEquals("no context (the lab off): the pairs decide, and the model isn't asked", pairs, reader.read("for", null))
        assertTrue(stand.asked.isEmpty())
        spatial.neural = Stand(setOf(pairs[1]), answers = false)
        assertEquals("no answer in time: the pairs decide", pairs, reader.read("for", "Thanks"))
    }

    @Test fun contextStartMovesInSteps() {
        assertEquals("short text is all read", "Hello there", NeuralWords.context("Hello there "))
        val long = (1..200).joinToString(" ") { "word$it" }
        val a = NeuralWords.context(long)
        val b = NeuralWords.context("$long more")
        assertTrue(a.length <= 600 && b.length <= 600)
        assertEquals("the next word's context begins where this one's does", a.substringBefore(' '), b.substringBefore(' '))
        assertTrue("from a word's start", long.contains(" " + a))
    }
}
