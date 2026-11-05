import {useEffect} from 'react';
import {setsEqual} from './operationTree';

// Unsaved edits survive a Jahia session expiry (WCAG 2.2.5): they are kept in this tab's
// sessionStorage with the server list they were made against (`base`), and restored after a
// re-login only if the server still holds that same list. Storage is a convenience: when it is
// unavailable the panel works the same, without recovery.

const DRAFT_KEY = 'jahia-mcp-community-server:draft';

const warn = (action, err) => console.warn(`MCP settings: could not ${action} the unsaved allow list draft`, err);

const removeDraft = () => {
    try {
        window.sessionStorage.removeItem(DRAFT_KEY);
    } catch (err) {
        warn('remove', err);
    }
};

const isStringArray = value => Array.isArray(value) && value.every(e => typeof e === 'string');

// The stored draft as a NEW Set when it was made against `serverEntries`, else null. A draft
// made against another list, or unreadable, is dropped: it must never be applied.
export const takeDraft = serverEntries => {
    let stored = null;
    try {
        const raw = window.sessionStorage.getItem(DRAFT_KEY);
        stored = raw ? JSON.parse(raw) : null;
    } catch (err) {
        warn('read', err);
        removeDraft();
        return null;
    }

    if (!stored) {
        return null;
    }

    const isUsable = isStringArray(stored.base) && isStringArray(stored.draft) &&
        setsEqual(new Set(stored.base), serverEntries);
    if (!isUsable) {
        removeDraft();
        return null;
    }

    return new Set(stored.draft);
};

// Keeps the draft in step with the edits once the server list is known (`isReady`).
export const useDraftPersistence = (isReady, saved, whitelist) => {
    useEffect(() => {
        if (!isReady) {
            return;
        }

        if (setsEqual(saved, whitelist)) {
            removeDraft();
            return;
        }

        try {
            window.sessionStorage.setItem(DRAFT_KEY, JSON.stringify({base: [...saved], draft: [...whitelist]}));
        } catch (err) {
            warn('store', err);
        }
    }, [isReady, saved, whitelist]);
};
