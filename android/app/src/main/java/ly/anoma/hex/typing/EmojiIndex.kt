package ly.anoma.hex.typing

import java.text.Normalizer

/** iPhone Hex's emoji index (`assets/emoji/index-en.txt`, a copy of `ios/HEXShared/FullKeyboard/Emoji/emoji-index-en.txt`,
 * built by `ios/scripts/build-emoji-data.py` from Unicode's emoji-test.txt and CLDR annotations, Unicode License v3,
 * and Hex's own chat words): every emoji with its name and search words, and each word's and phrase's emoji with a
 * weight. Read once into memory; immutable after. As iPhone Hex's `EmojiLibrary`. */
internal class EmojiIndex private constructor(
    private val emoji: List<String>,
    private val names: List<String>,
    /** Each entry's search words: its name's words, then its keywords', with [nameWords] the name's count. */
    private val words: List<List<String>>,
    private val nameWords: IntArray,
    private val families: IntArray,
    private val wordPostings: Map<String, String>,
    private val phrasePostings: Map<String, String>,
    /** The file's entry numbers to ours; -1 for an emoji this phone doesn't draw. */
    private val remap: IntArray,
) {
    /** An emoji a word names or describes, by its entry, with the index's weight: 1 for a one-word name, 0.9 to 1.2 for
     * a chat alias, keywords less. */
    data class Hit(val entry: Int, val weight: Float)

    val size get() = emoji.size
    fun emoji(entry: Int) = emoji[entry]
    fun name(entry: Int) = names[entry]
    /** Emoji whose names differ only by who does it ("man running", "woman running") share a family; -1 for none. */
    fun family(entry: Int) = families[entry]

    /** The emoji a folded word names or describes, strongest first in the file's order; empty when none. */
    fun hits(word: String): List<Hit> = postings(wordPostings[word])
    fun phraseHits(phrase: String): List<Hit> = postings(phrasePostings[phrase])

    private fun postings(line: String?): List<Hit> {
        if (line == null) return emptyList()
        val out = ArrayList<Hit>()
        for (part in line.split(',')) {
            val colon = part.indexOf(':')
            if (colon <= 0) continue
            val n = part.substring(0, colon).toIntOrNull() ?: continue
            val weight = part.substring(colon + 1).toFloatOrNull() ?: continue
            val entry = remap.getOrElse(n) { -1 }
            if (entry >= 0) out += Hit(entry, weight)
        }
        return out
    }

    /** The emoji a search finds: each of its words must start a word of the emoji's name or keywords. Whole name words
     * first, then name prefixes, then keywords, each in the index's order; among equals a short name the query fills
     * ("red heart") before a long one it only touches, then [boost] (your use, 0..1). As iPhone Hex's search. */
    fun search(query: String, limit: Int = 80, boost: (Int) -> Float = { 0f }): List<Int> {
        val terms = EmojiText.words(query).sortedByDescending { it.length }
        if (terms.isEmpty()) return emptyList()
        val scored = ArrayList<Pair<Int, Float>>()
        for (entry in emoji.indices) {
            val ws = words[entry]
            val inName = nameWords[entry]
            var total = 0f
            var all = true
            for (term in terms) {
                var best = 0f
                for ((i, w) in ws.withIndex()) {
                    if (!w.startsWith(term)) continue
                    val quality = (if (w.length == term.length) 2f else 1f) + (if (i < inName) 2f else 0f)
                    if (quality > best) best = quality
                    if (best == 4f) break
                }
                if (best == 0f) { all = false; break }
                total += best
            }
            if (!all) continue
            val fill = 0.3f * minOf(terms.size, inName) / maxOf(1, inName)
            scored += entry to total + fill + 0.6f * minOf(1f, boost(entry))
        }
        scored.sortWith(compareByDescending<Pair<Int, Float>> { it.second }.thenBy { it.first })
        return scored.take(limit).map { it.first }
    }

    companion object {
        const val ASSET = "emoji/index-en.txt"
        /** Emoji 13.1, the newest every phone this app runs on (Android 12) draws; a newer one is kept only if [draws]. */
        private const val ASSUMED_VERSION = 13.1

        /** Reads the index's lines; [draws] says whether this phone draws a newer emoji. Null for no entries. */
        fun parse(lines: Sequence<String>, draws: (String) -> Boolean = { true }): EmojiIndex? {
            val emoji = ArrayList<String>(2000); val names = ArrayList<String>(2000)
            val words = ArrayList<List<String>>(2000); val nameWords = ArrayList<Int>(2000); val families = ArrayList<Int>(2000)
            val remap = ArrayList<Int>(2000)
            val wordPostings = HashMap<String, String>(8000); val phrasePostings = HashMap<String, String>(3000)
            for (line in lines) {
                if (line.length < 3 || line[1] != '\t') continue
                val body = line.substring(2)
                when (line[0]) {
                    'W', 'P' -> {
                        val tab = body.indexOf('\t')
                        if (tab > 0) (if (line[0] == 'W') wordPostings else phrasePostings)[body.substring(0, tab)] = body.substring(tab + 1)
                    }
                    'E' -> {
                        // emoji, category, version, tones, family, name, search words ("name words|keyword words")
                        val f = body.split('\t')
                        if (f.size != 7) { remap += -1; continue }
                        val version = f[2].toDoubleOrNull() ?: 0.0
                        if (version > ASSUMED_VERSION && !draws(f[0])) { remap += -1; continue }
                        remap += emoji.size
                        emoji += f[0]; names += f[5]; families += f[4].toIntOrNull() ?: -1
                        val name = f[6].substringBefore('|').split(' ').filter { it.isNotEmpty() }
                        val keywords = f[6].substringAfter('|', "").split(' ').filter { it.isNotEmpty() }
                        words += name + keywords; nameWords += name.size
                    }
                }
            }
            if (emoji.isEmpty()) return null
            return EmojiIndex(emoji, names, words, nameWords.toIntArray(), families.toIntArray(), wordPostings, phrasePostings,
                remap.toIntArray())
        }
    }
}

/** Folding and word splitting as the index's (`build-emoji-data.py` folds the same way): iPhone Hex's `EmojiText`. */
internal object EmojiText {
    private val marks = Regex("\\p{Mn}+")
    /** NFD, combining marks dropped, lowercased, curly apostrophes straightened. */
    fun fold(text: String) = marks.replace(Normalizer.normalize(text.replace('’', '\''), Normalizer.Form.NFD), "").lowercase()
    /** Folded words: letters and digits, an apostrophe's tail dropped ("that's" → "that"). */
    fun words(text: String): List<String> = fold(text).split(Regex("[^\\p{L}\\p{N}']+"))
        .mapNotNull { part -> part.substringBefore('\'').takeIf { it.isNotEmpty() } }
}

/** Labs › Keyboard › Emoji shortcodes: the ":name" right before the cursor and the emoji it names, as iPhone Hex's
 * `EmojiShortcode`. A token is a colon and a name after whitespace or at the field's start, so a colon inside a word
 * ("abc:pizza") is none, and chat emoticons (":-D", ":+1") stay text. */
internal object EmojiShortcode {
    /** The shortest name: two letters or digits, so ":pi" suggests and ":p" is just a colon. */
    const val MINIMUM_NAME = 2
    /** A keyword weighs under this; a name or an alias at or over it. */
    const val STRONG_WEIGHT = 0.85f

    /** A ":name" as typed: [name] after the colon, [text] the whole token a pick takes back. */
    data class Token(val name: String, val text: String) {
        /** The name in the index's words: lowercased, "_", "+" and "-" as spaces ("sweat_smile" is "sweat smile"). */
        val lookup get() = name.lowercase().split('_', '+', '-').filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun nameChar(c: Char) = c.code < 128 && (c.isLetterOrDigit() || c == '_' || c == '+' || c == '-')

    /** The token that ends [before], or null. */
    fun token(before: String?): Token? {
        if (before == null) return null
        var i = before.length
        while (i > 0 && nameChar(before[i - 1])) i--
        if (i == 0 || before[i - 1] != ':') return null
        if (i - 1 > 0 && !before[i - 2].isWhitespace()) return null
        val name = before.substring(i)
        val first = name.firstOrNull() ?: return null
        if (first.code >= 128 || !first.isLetterOrDigit() || name.count { it.isLetterOrDigit() } < MINIMUM_NAME) return null
        return Token(name, ":$name")
    }

    /** The strip's emoji for [token], best first: names and aliases it is exactly, then the names and keywords it
     * starts; one per family, so a running man and a running woman don't take two slots. */
    fun matches(token: Token, index: EmojiIndex, limit: Int = 3): List<Int> {
        val picked = ArrayList<Int>(); val families = HashSet<Int>()
        fun take(entry: Int) {
            if (picked.size >= limit || entry in picked) return
            val family = index.family(entry)
            if (family >= 0 && !families.add(family)) return
            picked += entry
        }
        exact(token, index).forEach { take(it.entry) }
        if (picked.size < limit) index.search(token.lookup, limit + 8).forEach(::take)
        return picked
    }

    /** The emoji a complete name names, which a closing ":" puts in: a name or alias, never a prefix. */
    fun completion(before: String?, index: EmojiIndex): Pair<Token, Int>? {
        val token = token(before) ?: return null
        return exact(token, index).firstOrNull()?.let { token to it.entry }
    }

    private fun exact(token: Token, index: EmojiIndex): List<EmojiIndex.Hit> {
        val name = token.lookup
        if (name.isEmpty()) return emptyList()
        return (if (' ' in name) index.phraseHits(name) else index.hits(name))
            .filter { it.weight >= STRONG_WEIGHT }.sortedWith(compareByDescending<EmojiIndex.Hit> { it.weight }.thenBy { it.entry })
    }
}
