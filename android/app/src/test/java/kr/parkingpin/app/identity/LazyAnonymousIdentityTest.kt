package kr.parkingpin.app.identity

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A scripted `AnonymousSignIn` that counts how often it was actually asked to sign in, and
 * remembers the uid afterwards the way the SDK's persisted session does.
 */
private class FakeAnonymousSignIn(
    private val result: () -> String,
) : AnonymousSignIn {

    var signInCount: Int = 0
        private set

    private var uid: String? = null

    override fun currentUid(): String? = uid

    override suspend fun signIn(): String {
        signInCount++
        return result().also { uid = it }
    }
}

/**
 * **docs/07 §4 / §13.** Anonymous Auth happens when a backend feature needs a caller — not
 * at launch, not once per call, and never in a way that can take a screen down with it.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LazyAnonymousIdentityTest {

    @Test
    fun `constructing the identity signs nobody in`() = runTest {
        // Arrange
        val signIn = FakeAnonymousSignIn { "uid-1" }

        // Act — build it, as `AppContainer` does, and then do nothing with it.
        LazyAnonymousIdentity(signIn)

        // Assert — docs/04_IOS §14: not necessarily before home can render. Nothing here
        // reaches the network until a caller asks.
        assertEquals(0, signIn.signInCount)
    }

    @Test
    fun `the first caller signs in and gets the uid`() = runTest {
        // Arrange
        val signIn = FakeAnonymousSignIn { "uid-1" }
        val identity = LazyAnonymousIdentity(signIn)

        // Act
        val uid = identity.uid()

        // Assert
        assertEquals("uid-1", uid)
        assertEquals(1, signIn.signInCount)
    }

    @Test
    fun `later callers reuse the session instead of signing in again`() = runTest {
        // Arrange
        val signIn = FakeAnonymousSignIn { "uid-1" }
        val identity = LazyAnonymousIdentity(signIn)

        // Act
        repeat(5) { identity.uid() }

        // Assert — a second sign-in would mint a second anonymous account and silently
        // abandon whatever server state was keyed to the first.
        assertEquals(1, signIn.signInCount)
    }

    @Test
    fun `concurrent callers produce one account, not two`() = runTest {
        // Arrange — a sign-in that cannot finish until the test lets it, so both callers
        // are provably inside `uid()` at the same moment.
        val release = CompletableDeferred<Unit>()
        val signIn = FakeAnonymousSignIn { "uid-1" }
        val blocking = object : AnonymousSignIn {
            override fun currentUid(): String? = signIn.currentUid()
            override suspend fun signIn(): String {
                release.await()
                return signIn.signIn()
            }
        }
        val identity = LazyAnonymousIdentity(blocking)

        // Act
        val first = async { identity.uid() }
        val second = async { identity.uid() }
        release.complete(Unit)

        // Assert
        assertEquals("uid-1", first.await())
        assertEquals("uid-1", second.await())
        assertEquals(1, signIn.signInCount)
    }

    @Test
    fun `a failed sign-in answers null rather than throwing at the caller`() = runTest {
        // Arrange — offline is the ordinary case, not an exceptional one.
        val identity = LazyAnonymousIdentity(FakeAnonymousSignIn { throw IOException("offline") })

        // Act
        val uid = identity.uid()

        // Assert — CLAUDE.md "권한 거부는 앱 전체 failure가 아님" applies here too: every
        // parking feature is local, so nothing on screen may depend on this succeeding.
        assertNull(uid)
    }

    @Test
    fun `a failure is not cached — the next caller tries again`() = runTest {
        // Arrange — fails once, then succeeds, as a device coming back online would.
        var attempt = 0
        val signIn = FakeAnonymousSignIn {
            attempt++
            if (attempt == 1) throw IOException("offline") else "uid-1"
        }
        val identity = LazyAnonymousIdentity(signIn)

        // Act
        val whileOffline = identity.uid()
        val whenBack = identity.uid()

        // Assert
        assertNull(whileOffline)
        assertEquals("uid-1", whenBack)
        assertEquals(2, signIn.signInCount)
    }

    @Test
    fun `the unavailable identity answers null without pretending to have a uid`() = runTest {
        // Act — the build with no Firebase project (CI, fork PRs).
        val uid = UnavailableAnonymousIdentity.uid()

        // Assert — a placeholder uid would be accepted here and rejected by the backend,
        // which is a worse failure than none.
        assertNull(uid)
    }

    @Test
    fun `cancelling the caller cancels the sign-in instead of answering null`() = runTest {
        // Arrange — a sign-in that never finishes on its own.
        val neverFinishes = object : AnonymousSignIn {
            override fun currentUid(): String? = null
            override suspend fun signIn(): String = CompletableDeferred<String>().await()
        }
        val identity = LazyAnonymousIdentity(neverFinishes)
        val caller = async { identity.uid() }
        runCurrent()

        // Act — the feature that wanted a uid went away.
        caller.cancel()

        // Assert — swallowing the CancellationException here would leave the caller's
        // coroutine believing it finished, and would break structured concurrency.
        assertTrue(caller.isCancelled)
    }

    @Test
    fun `a restored session needs no sign-in at all`() = runTest {
        // Arrange — the SDK restores the persisted session locally, with no network call.
        val restored = object : AnonymousSignIn {
            var signInCount = 0
            override fun currentUid(): String = "uid-restored"
            override suspend fun signIn(): String {
                signInCount++
                return "uid-new"
            }
        }
        val identity = LazyAnonymousIdentity(restored)

        // Act
        val uid = identity.uid()

        // Assert
        assertEquals("uid-restored", uid)
        assertEquals(0, restored.signInCount)
    }
}
