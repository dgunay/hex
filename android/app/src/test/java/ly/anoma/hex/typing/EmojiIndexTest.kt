package ly.anoma.hex.typing

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiIndexTest {
    private val index = EmojiIndex.parse(File("src/main/assets/${EmojiIndex.ASSET}").readLines().asSequence())!!
    private fun emoji(list: List<Int>) = list.map(index::emoji)

    @Test fun searchFindsEmojiByTheirWords() {
        assertEquals("🔥", emoji(index.search("fire")).first())
        assertTrue(emoji(index.search("laugh")).containsAll(listOf("😆", "😂")))
        assertEquals("❤️", emoji(index.search("red heart")).first())
        assertTrue("every word must match", index.search("fire zzzz").isEmpty())
    }

    @Test fun aShortcodeIsAColonAndAName() {
        assertEquals(":fire", EmojiShortcode.token("so hot :fire")?.text)
        assertEquals(":sweat_smile", EmojiShortcode.token(":sweat_smile")?.text)
        assertNull("one letter is just a colon", EmojiShortcode.token("ok :p"))
        assertNull("inside a word", EmojiShortcode.token("abc:pizza"))
        assertNull("an emoticon", EmojiShortcode.token("lol :-D"))
        assertNull("time", EmojiShortcode.token("at 10:30"))
    }

    @Test fun aShortcodeOffersItsEmoji() {
        assertEquals("🔥", emoji(EmojiShortcode.matches(EmojiShortcode.token(":fire")!!, index)).first())
        assertEquals("🍕", emoji(EmojiShortcode.matches(EmojiShortcode.token(":piz")!!, index)).first())
        assertEquals("🔥", EmojiShortcode.completion("hot :fire", index)?.second?.let(index::emoji))
        assertNull("a prefix is no name for the closing colon", EmojiShortcode.completion(":piz", index))
    }

    @Test fun anEmojiThePhoneCantDrawIsLeftOut() {
        val all = EmojiIndex.parse(File("src/main/assets/${EmojiIndex.ASSET}").readLines().asSequence())!!
        val old = EmojiIndex.parse(File("src/main/assets/${EmojiIndex.ASSET}").readLines().asSequence()) { false }!!
        assertTrue(old.size < all.size)
        assertEquals("🔥", old.emoji(old.search("fire").first()))
    }
}
