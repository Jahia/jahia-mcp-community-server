import React, {useCallback, useEffect, useRef, useState} from 'react';
import PropTypes from 'prop-types';

// Polite announcements. Every call renders the text in a NEW node (keyed by a counter), so a
// message identical to the previous one is still announced.
export const useAnnouncer = () => {
    const [message, setMessage] = useState({text: '', seq: 0});
    const announce = useCallback(text => setMessage(prev => ({text, seq: prev.seq + 1})), []);
    return [message, announce];
};

// Persistent live region: present before any message is written to it.
export const LiveRegion = ({message, className}) => (
    <div id="mcp-live" className={className} role="status" aria-live="polite">
        {message.text && <span key={message.seq}>{message.text}</span>}
    </div>
);

LiveRegion.propTypes = {
    message: PropTypes.shape({text: PropTypes.string, seq: PropTypes.number}).isRequired,
    className: PropTypes.string
};

const isFocusLost = () => {
    const active = document.activeElement;
    return !active || active === document.body || !active.isConnected;
};

// WCAG 2.4.3: when an action removes the focused control (Discard, Dismiss, Remove, a confirmed
// Clear…), move focus to a defined target instead of letting it fall to <body>. The caller
// registers a finder `root => element`; after the next commit, and only if focus was lost, the
// target is focused. Focus left on a control that still exists is never moved.
export const useFocusRecovery = rootRef => {
    const pending = useRef(null);
    useEffect(() => {
        const find = pending.current;
        pending.current = null;
        if (find && isFocusLost() && rootRef.current) {
            find(rootRef.current)?.focus();
        }
    });
    return useCallback(find => {
        pending.current = find;
    }, []);
};
