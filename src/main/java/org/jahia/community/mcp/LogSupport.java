package org.jahia.community.mcp;

import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.usermanager.JahiaUser;

/** Helpers for safe, attributable log lines. */
public final class LogSupport {

    private static final int MAX_LOGGED_CHARS = 200;
    private static final String UNKNOWN_USER = "unknown";

    private LogSupport() {
    }

    /**
     * Makes caller-supplied text safe to log: CR, LF and every other control character become a
     * space (no forged log lines) and the result is cut to a bounded length.
     */
    public static String clean(final String text) {
        if (text == null) {
            return "null";
        }
        final String cleaned = text.replaceAll("[\\p{Cntrl}\\u2028\\u2029]+", " ").strip();
        return cleaned.length() <= MAX_LOGGED_CHARS ? cleaned : cleaned.substring(0, MAX_LOGGED_CHARS) + "...";
    }

    /** Name of the user bound to the current thread (the acting administrator), cleaned for logging. */
    public static String actingUser() {
        try {
            final JahiaUser user = JCRSessionFactory.getInstance().getCurrentUser();
            return user == null ? UNKNOWN_USER : clean(user.getUsername());
        } catch (RuntimeException ex) {
            return UNKNOWN_USER;
        }
    }
}
