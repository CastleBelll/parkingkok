package com.parkingpin.app.entitlement

/**
 * What the app believes about this user's subscription (docs/08_SUBSCRIPTION_REFERRAL_ADS.md §3).
 *
 * The six states are the contract's, not a convenience subset: [GRACE] and [BILLING_ISSUE]
 * are the two that decide whether a lapsing subscriber keeps their features while Google
 * retries a payment, and a build that collapsed them into [FREE] would take Plus away from
 * someone who is still paying for it.
 *
 * Nothing produces anything but [FREE] and [PLUS_ACTIVE] yet — see [plusEntitlement].
 */
enum class PlusEntitlement {
    /** Before the first store query answers. Fails closed, like every other unknown here. */
    UNKNOWN,
    FREE,
    PLUS_ACTIVE,

    /** Payment failed, the store is retrying, and access continues meanwhile. */
    GRACE,

    /** The store is asking the user to fix a payment method. Access continues. */
    BILLING_ISSUE,
    EXPIRED,
    ;

    /**
     * The one question a feature asks. [UNKNOWN] is false: a wrong false costs a disabled
     * control for a second, a wrong true gives a paid feature away.
     */
    val allowsPlusFeatures: Boolean
        get() = when (this) {
            PLUS_ACTIVE, GRACE, BILLING_ISSUE -> true
            UNKNOWN, FREE, EXPIRED -> false
        }
}

/**
 * **The single place Plus is decided.** docs/06 §7a for the widget, generalised because
 * every other Plus feature in docs/08 §1 — auto departure, unlimited history, export,
 * advanced detector controls — needs the same answer and must not each invent one.
 *
 * ### Why it is hardcoded
 * The app ships free first: no Play Billing, no paywall (docs/08 §1a). So this answers
 * [PlusEntitlement.PLUS_ACTIVE] on the DEV and STAGING builds, where the Plus paths have to
 * be built and tested, and [PlusEntitlement.FREE] on the release build, where they must be
 * invisible. M6 replaces this body with a verified purchase token and changes nothing else.
 *
 * The parameter is the build's own debuggable flag rather than a [android.content.Context],
 * so both branches are reachable from a plain JVM test.
 *
 * @param debuggable true for the DEV and STAGING builds, false for the PROD release build.
 */
fun plusEntitlement(debuggable: Boolean): PlusEntitlement =
    if (debuggable) PlusEntitlement.PLUS_ACTIVE else PlusEntitlement.FREE
