package com.vocab.anki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VocabTest {

    @Test fun extractsWordFromCommonPhrasings() {
        assertEquals("ubiquitous", SpokenQuery.extractWord("What does ubiquitous mean?"))
        assertEquals("ubiquitous", SpokenQuery.extractWord("what does the word ubiquitous mean"))
        assertEquals("serendipity", SpokenQuery.extractWord("define serendipity"))
        assertEquals("serendipity", SpokenQuery.extractWord("Definition of serendipity"))
        assertEquals("ephemeral", SpokenQuery.extractWord("what's the meaning of ephemeral"))
        assertEquals("gazebo", SpokenQuery.extractWord("What is a gazebo"))
        assertEquals("laconic", SpokenQuery.extractWord("Laconic"))
    }

    @Test fun parsesYesAndNo() {
        assertEquals(true, SpokenQuery.parseYesNo("yes"))
        assertEquals(true, SpokenQuery.parseYesNo("Yeah add it"))
        assertEquals(true, SpokenQuery.parseYesNo("sure"))
        assertEquals(false, SpokenQuery.parseYesNo("no"))
        assertEquals(false, SpokenQuery.parseYesNo("no thanks"))
        assertEquals(false, SpokenQuery.parseYesNo("don't add it"))
        assertNull(SpokenQuery.parseYesNo("banana"))
    }

    @Test fun parsesDictionaryResponse() {
        val json = javaClass.classLoader!!.getResource("light.json")!!.readText()
        val entry = Dictionary.parse(json, "light")
        assertEquals("light", entry.word)
        assertTrue("expected several parts of speech", entry.meanings.size >= 2)
        assertTrue(entry.meanings.isNotEmpty())
        assertTrue(entry.meanings.size <= 3)
        assertTrue(entry.meanings.all { it.definitions.size in 1..4 })
        // Parts of speech are merged, not repeated.
        assertEquals(entry.meanings.size, entry.meanings.map { it.partOfSpeech }.toSet().size)
        // Senses are flattened in order, keeping each definition's own example.
        assertEquals(entry.meanings.sumOf { it.definitions.size }, entry.senses.size)
        assertEquals(entry.meanings[0].definitions[0].text, entry.senses[0].definition)
        assertEquals(entry.meanings[0].partOfSpeech, entry.senses[0].partOfSpeech)
        println(entry.meanings.joinToString("\n") { m -> m.partOfSpeech + ": " + m.definitions.joinToString(" | ") { it.text } })
    }

    @Test fun parsesSimpleWord() {
        val json = javaClass.classLoader!!.getResource("ephemeral.json")!!.readText()
        val entry = Dictionary.parse(json, "ephemeral")
        assertEquals("ephemeral", entry.word)
        assertTrue(entry.phonetic!!.isNotBlank())
    }

    @Test(expected = WordNotFoundException::class)
    fun emptyResponseIsNotFound() {
        Dictionary.parse("[]", "zzzz")
    }
}
