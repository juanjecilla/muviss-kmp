package com.codingpit.muviss.core.billing

/**
 * Whether the user currently holds the paid entitlement.
 *
 * Three states rather than a `Boolean` because [Unknown] is a real and common
 * one: a store SDK has to reach the network before it can answer, and the
 * honest answer in the meantime is neither yes nor no. Callers that must act
 * decide for themselves which way to resolve it — [isEntitled] treats it as
 * "not yet", so an unanswered check never grants access, while a paywall can
 * show a spinner instead of a purchase button.
 */
enum class Entitlement {
    Active,
    Inactive,
    Unknown,
    ;

    /** Fail-closed reading, for the gate. [Unknown] is not a grant. */
    val isEntitled: Boolean get() = this == Active
}
