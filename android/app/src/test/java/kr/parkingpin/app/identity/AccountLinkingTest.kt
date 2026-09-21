package kr.parkingpin.app.identity

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * docs/07 §13a: signing in is a **link**, and the uid it produces is the one this device
 * already had.
 *
 * The rule is invisible today — nothing is keyed to the uid yet — and expensive the moment
 * anything is. That is precisely why it is pinned now: a regression here would ship green
 * and surface as a user losing their referral credit six months later.
 */
class AccountLinkingTest {

    @Test
    fun `linking keeps the uid the device already had`() = runTest {
        // Arrange — a device that has been running anonymously.
        val auth = FakeAuth(uid = "anon-1")
        val linking = FakeAccountLinking(auth)
        val identity = LinkingAccountIdentity(FixedAnonymousIdentity(auth, "anon-1"), linking)

        // Act
        val result = identity.signIn(AccountProvider.GOOGLE, credential = "token")

        // Assert — same uid, provider attached. This is the whole feature.
        assertEquals(AccountLinkResult.Linked(uid = "anon-1", previousUid = "anon-1"), result)
        assertEquals(AccountState.Linked("anon-1", AccountProvider.GOOGLE), identity.state())
        assertTrue("it must link, never sign in afresh", linking.linkCalls == 1)
    }

    @Test
    fun `a device with no identity yet gets one first, and links to it`() = runTest {
        // §13a: the tempting shortcut is to sign in with the provider directly when there is
        // no anonymous user. That produces a different uid than this device would otherwise
        // have had, which is harmless now and is the habit that breaks the rule later.
        val auth = FakeAuth(uid = null)
        val linking = FakeAccountLinking(auth)
        val anonymous = FixedAnonymousIdentity(auth, "anon-created-now")
        val identity = LinkingAccountIdentity(anonymous, linking)

        val result = identity.signIn(AccountProvider.APPLE, credential = "token")

        assertEquals(1, anonymous.uidCalls)
        assertEquals(
            AccountLinkResult.Linked(uid = "anon-created-now", previousUid = "anon-created-now"),
            result,
        )
    }

    @Test
    fun `a credential already used elsewhere is refused, not switched to`() = runTest {
        // docs/07 §13b. Switching abandons this device's uid silently and unrecoverably.
        val auth = FakeAuth(uid = "anon-1")
        val linking = FakeAccountLinking(auth, collide = true)
        val identity = LinkingAccountIdentity(FixedAnonymousIdentity(auth, "anon-1"), linking)

        val result = identity.signIn(AccountProvider.GOOGLE, credential = "token")

        assertEquals(AccountLinkResult.AlreadyLinkedElsewhere, result)
        assertEquals(
            "the anonymous account is untouched, and so is everything keyed to it",
            AccountState.Anonymous("anon-1"),
            identity.state(),
        )
    }

    @Test
    fun `no identity available means no link is attempted`() = runTest {
        // Offline, or a build with no Firebase project. Failing here rather than letting the
        // SDK phrase it keeps the caller's answer the same in both cases.
        val linking = FakeAccountLinking(FakeAuth(uid = null))
        val identity = LinkingAccountIdentity(UnavailableAnonymousIdentity, linking)

        val result = identity.signIn(AccountProvider.GOOGLE, credential = "token")

        assertTrue(result is AccountLinkResult.Failed)
        assertEquals(0, linking.linkCalls)
    }

    // ── doubles ─────────────────────────────────────────────────────────────────────

    /**
     * One holder for both doubles, because in production there is one `FirebaseAuth`: the
     * anonymous sign-in and the link see the same current user. Keeping them separate is
     * what made the first version of these tests fail for a reason the app does not have.
     */
    private class FakeAuth(var uid: String? = null) {
        var provider: AccountProvider? = null
    }

    private class FixedAnonymousIdentity(
        private val auth: FakeAuth,
        private val newUid: String,
    ) : AnonymousIdentity {
        var uidCalls = 0
            private set

        override suspend fun uid(): String {
            uidCalls += 1
            return auth.uid ?: newUid.also { auth.uid = it }
        }
    }

    /** Models `linkWithCredential`: the uid survives, a provider is added. */
    private class FakeAccountLinking(
        private val auth: FakeAuth,
        private val collide: Boolean = false,
    ) : AccountLinking {
        var linkCalls = 0
            private set

        override fun currentState(): AccountState {
            val current = auth.uid ?: return AccountState.None
            return auth.provider?.let { AccountState.Linked(current, it) }
                ?: AccountState.Anonymous(current)
        }

        override suspend fun link(provider: AccountProvider, credential: String): AccountLinkResult {
            linkCalls += 1
            if (collide) return AccountLinkResult.AlreadyLinkedElsewhere
            val current = auth.uid ?: return AccountLinkResult.Failed("no current user")
            auth.provider = provider
            return AccountLinkResult.Linked(uid = current, previousUid = current)
        }

        override suspend fun signOut() {
            auth.provider = null
        }
    }
}
