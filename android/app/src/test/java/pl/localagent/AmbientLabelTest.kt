package pl.localagent

import org.junit.Assert.assertEquals
import org.junit.Test

class AmbientLabelTest {
    private fun label(vararg names: String) = AmbientListener.toLabel(names.toList())

    @Test fun speechIsConversation() = assertEquals("rozmowa", label("Speech", "Inside, small room"))
    @Test fun tvWinsOverSpeech() = assertEquals("TV/radio", label("Speech", "Television"))
    @Test fun keyboard() = assertEquals("pisanie na klawiaturze", label("Computer keyboard", "Typing"))
    @Test fun musicGenres() = assertEquals("muzyka", label("Pop music"))
    @Test fun silence() = assertEquals("cisza", label("Silence"))
    @Test fun kitchen() = assertEquals("kuchnia", label("Cutlery, silverware"))
    @Test fun nothing() = assertEquals("inne", label())
    @Test fun unknownKeepsTopClass() = assertEquals("Dog", label("Dog", "Animal"))
}
