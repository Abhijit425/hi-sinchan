package com.abhijit.hisinchan

import com.abhijit.hisinchan.ai.ConversationEngine
import com.abhijit.hisinchan.avatar.Emotion
import com.abhijit.hisinchan.memory.MemoryCommand
import com.abhijit.hisinchan.memory.MemoryCommandParser
import com.abhijit.hisinchan.memory.MemoryRepository
import com.abhijit.hisinchan.voice.WakePhrase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreLogicTest {

    @Test fun rememberVariants() {
        assertEquals(MemoryCommand.Remember("My name is Abhijit"), MemoryCommandParser.parse("Hi Sinchan, remember that my name is Abhijit."))
        assertEquals(MemoryCommand.Remember("My favourite colour is blue"), MemoryCommandParser.parse("Sinchan, remember that my favourite colour is blue."))
        assertEquals(MemoryCommand.Remember("I prefer short answers"), MemoryCommandParser.parse("Memorise that I prefer short answers."))
        assertEquals(MemoryCommand.Remember("I work with QAD ERP"), MemoryCommandParser.parse("Remember that I work with QAD ERP"))
        assertEquals(MemoryCommand.Remember("My birthday is 5 May"), MemoryCommandParser.parse("please don't forget that my birthday is 5 May"))
    }

    @Test fun listForgetShow() {
        assertEquals(MemoryCommand.ListAll, MemoryCommandParser.parse("What do you remember about me?"))
        assertEquals(MemoryCommand.ShowScreen, MemoryCommandParser.parse("Show me everything you have memorised."))
        assertEquals(MemoryCommand.Forget("my favourite colour"), MemoryCommandParser.parse("Forget my favourite colour."))
        assertEquals(MemoryCommand.ForgetEverything, MemoryCommandParser.parse("forget everything"))
    }

    @Test fun ordinaryChatIsNotMemory() {
        assertNull(MemoryCommandParser.parse("Hi Sinchan, what is my name?"))
        assertNull(MemoryCommandParser.parse("Do you remember the capital of France?"))
        assertNull(MemoryCommandParser.parse("I feel tired today"))
        assertNull(MemoryCommandParser.parse("remember when we talked about space?"))
    }

    @Test fun secondPerson() {
        assertEquals("your name is Abhijit", MemoryCommandParser.toSecondPerson("My name is Abhijit"))
        assertEquals("you prefer short answers", MemoryCommandParser.toSecondPerson("I prefer short answers"))
        assertEquals("you're a developer", MemoryCommandParser.toSecondPerson("I'm a developer"))
    }

    @Test fun subjectsUpdateInsteadOfDuplicate() {
        assertEquals(MemoryRepository.subjectOf("My favourite colour is blue"), MemoryRepository.subjectOf("my favorite color is green"))
        assertNull(MemoryRepository.subjectOf("I prefer short answers"))
        assertTrue("colour" in MemoryRepository.keywords("my favourite colour"))
    }

    @Test fun emotionTag() {
        val (e, text) = ConversationEngine.extractEmotion("[emotion:excited] Wow, that's great!")
        assertEquals(Emotion.EXCITED, e)
        assertEquals("Wow, that's great!", text)
        val (e2, t2) = ConversationEngine.extractEmotion("No tag here.")
        assertNull(e2)
        assertEquals("No tag here.", t2)
        assertEquals("Bold text and item", ConversationEngine.cleanForSpeech("**Bold** text and\n- item").replace("\n", " "))
    }

    @Test fun wakePhrase() {
        assertTrue(WakePhrase.matches("hi sin chan", false))
        assertTrue(WakePhrase.matches("hey shin chan", true))
        assertTrue(WakePhrase.matches("sin chan", false))
        assertFalse(WakePhrase.matches("sin chan", true))
        assertFalse(WakePhrase.matches("[unk]", false))
        assertFalse(WakePhrase.matches("chandelier", false))
    }
}
