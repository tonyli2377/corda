package net.corda.core.crypto

import net.corda.core.contracts.PartyAndReference
import net.corda.core.serialization.OpaqueBytes
import java.security.PublicKey

class Party(val name: String, owningKey: CompositeKey) : AnonymousParty(owningKey) {
    /** A helper constructor that converts the given [PublicKey] in to a [CompositeKey] with a single node */
    constructor(name: String, owningKey: PublicKey) : this(name, owningKey.composite)
    override fun toString() = name
}