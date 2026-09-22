package com.insta360.kmpsdk.demo.touchscene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SceneLabelVocabularyTest {
    @Test
    fun `unknown English labels cannot enter Chinese speech`() {
        assertEquals("人", SceneLabelVocabulary.translate(" Person "))
        assertEquals("风景", SceneLabelVocabulary.translate("风景"))
        assertNull(SceneLabelVocabulary.translate("unmapped landscape"))
        assertNull(SceneLabelVocabulary.translate(""))
    }

    @Test
    fun `unsupported high confidence labels do not hide supported lower labels`() {
        assertEquals(
            listOf("人", "狗", "树"),
            SceneLabelVocabulary.topNames(
                listOf("unknown object", "person", "dog", "Person", "tree", "cat"),
            ),
        )
        assertEquals(emptyList<String>(), SceneLabelVocabulary.topNames(listOf("unknown", "other")))
    }
}
