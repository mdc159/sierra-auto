package com.mdc159.sierra.phone

import androidx.lifecycle.ViewModel

/**
 * The phone conversation state that must survive activity recreation (rotation, dark-mode
 * change, window resize): the rolling context with its bridge identity, the visible
 * transcript, and the endpoint that context belongs to. Before this existed, a recreation
 * silently started a fresh [PhoneConversation] — the visible transcript disappeared and
 * the next turn carried a different [PhoneConversation.id], splitting one bridge session
 * into two.
 *
 * The activity and its jobs are still destroyed on recreation (see onStop: going to the
 * background ends a spoken conversation). Only the state above is retained; an in-flight
 * turn is cancelled with the old activity and simply never records its reply. Holds no
 * resources, so [onCleared] needs to do nothing.
 */
internal class PhoneViewModel : ViewModel() {

    /** The rolling context and the bridge conversation identity it owns. */
    val conversation = PhoneConversation()

    /** The readable You/Sierra lines the screen shows. */
    val transcript = TranscriptLog()

    /**
     * Endpoint identity the current context belongs to. Kept here (not in the activity)
     * so a recreation cannot make the phone forget which endpoint the surviving context
     * was built for: switching endpoints afterwards must still clear it.
     */
    var endpointKey: String? = null

    /**
     * True once the effective settings have been loaded once (cold start). Until then the
     * agent line shows a neutral "checking" state instead of guessing from half-loaded
     * values; a recreation keeps the loaded state, so only the true first start waits.
     */
    var settingsLoaded: Boolean = false
}
