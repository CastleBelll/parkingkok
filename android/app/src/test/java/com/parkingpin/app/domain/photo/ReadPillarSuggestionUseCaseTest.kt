package com.parkingpin.app.domain.photo

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * §6a "When it fails": recognition failing is the normal case, and every way it fails
 * looks the same from here.
 */
class ReadPillarSuggestionUseCaseTest {

    private val photo = PhotoSource { ByteArrayInputStream(ByteArray(0)) }

    @Test
    fun `no text found is an empty suggestion and nothing else`() = runTest {
        // Arrange
        val readPillar = ReadPillarSuggestionUseCase(reader = { emptyList() })

        // Act
        val suggestion = readPillar(photo)

        // Assert — no error type to report, because there is nothing to tell the user.
        assertEquals(PillarSuggestion.NONE, suggestion)
    }

    @Test
    fun `a recogniser that takes too long is the same as one that found nothing`() = runTest {
        // Arrange — "the model is unavailable or takes too long → same as no text found".
        val timeout = 100L
        val readPillar = ReadPillarSuggestionUseCase(
            reader = {
                delay(timeout * 10)
                listOf("B3")
            },
            timeoutMillis = timeout,
        )

        // Act
        val suggestion = readPillar(photo)

        // Assert
        assertEquals(PillarSuggestion.NONE, suggestion)
    }

    @Test
    fun `what was read is parsed with the existing rules`() = runTest {
        val readPillar = ReadPillarSuggestionUseCase(reader = { listOf("B3", "A구역 142") })

        assertEquals(PillarSuggestion("B3", "A구역", "142"), readPillar(photo))
    }
}
