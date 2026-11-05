package org.jahia.community.mcp.access;

import java.util.Objects;

/**
 * Why the gate refused a document: a typed {@link Kind} (callers pick recovery hints from it, never
 * from the wording) and a client-safe message that never echoes document content.
 */
public final class Denial {

    /** Closed set of refusal causes. */
    public enum Kind {
        /** The document is not valid GraphQL (a syntax error, not a policy refusal). */
        PARSE,
        /** The document uses a named fragment spread. */
        NAMED_SPREAD,
        /** The selection could not be analysed (including documents nested too deeply). */
        UNANALYSABLE,
        /** The operation can never run through the tool, whatever the whitelist says. */
        NEVER_ALLOWED,
        /** A query under a namespace that the read-only default refuses. */
        PROTECTED_QUERY,
        /** A mutation the whitelist does not cover (including the empty, read-only default). */
        MUTATION_NOT_ALLOWED,
        /** A subscription. */
        SUBSCRIPTION,
        /** An operation that the configured whitelist does not cover. */
        NOT_WHITELISTED
    }

    private final Kind kind;
    private final String message;

    public Denial(final Kind kind, final String message) {
        this.kind = Objects.requireNonNull(kind);
        this.message = Objects.requireNonNull(message);
    }

    public Kind kind() {
        return kind;
    }

    /** Client-safe, human-readable reason. */
    public String message() {
        return message;
    }

    @Override
    public boolean equals(final Object o) {
        return o instanceof Denial && kind == ((Denial) o).kind && message.equals(((Denial) o).message);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, message);
    }

    @Override
    public String toString() {
        return kind + ": " + message;
    }
}
