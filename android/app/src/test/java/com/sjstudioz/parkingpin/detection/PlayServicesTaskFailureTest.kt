package com.sjstudioz.parkingpin.detection

import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Status
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PlayServicesTaskFailureTest {

    @Test
    fun `a subscription that succeeded has no failure reason`() = runTest {
        // Arrange
        val task = Tasks.forResult<Void>(null)

        // Act
        val reason = task.failureReason()

        // Assert
        assertNull(reason)
    }

    @Test
    fun `a rejected subscription is reported by its status code alone`() = runTest {
        // Arrange
        val task = Tasks.forException<Void>(ApiException(Status(API_UNAVAILABLE)))

        // Act
        val reason = task.failureReason()

        // Assert
        assertEquals("ApiException statusCode=$API_UNAVAILABLE", reason)
    }

    @Test
    fun `a revoked permission is left for the call site to handle`() {
        // Arrange — the call site must catch it, so lint can see the revocation is handled
        val task = Tasks.forException<Void>(SecurityException("ACTIVITY_RECOGNITION denied"))

        // Act + Assert
        assertThrows(SecurityException::class.java) {
            runBlocking { task.failureReason() }
        }
    }

    private companion object {
        const val API_UNAVAILABLE = 17
    }
}
