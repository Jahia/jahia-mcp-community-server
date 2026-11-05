import React, {useEffect, useRef} from 'react';
import PropTypes from 'prop-types';
import styles from './CommunityMcpConfig.scss';

// Modal confirmation (role=alertdialog). Focus starts on Cancel (the safe choice), Tab stays
// inside the dialog (also against mouse clicks outside it), Escape cancels, and focus returns
// to the control that opened it.
export const ConfirmDialog = ({title, body, confirmLabel, cancelLabel, onConfirm, onCancel}) => {
    const dialogRef = useRef(null);
    const cancelRef = useRef(null);

    useEffect(() => {
        const opener = document.activeElement;
        cancelRef.current.focus();
        // The page behind is not inert: focus that still reaches it (a click outside the
        // dialog, assistive technology) is sent back to Cancel, the safe choice.
        const keepFocusInside = e => {
            if (dialogRef.current && !dialogRef.current.contains(e.target)) {
                cancelRef.current?.focus();
            }
        };

        document.addEventListener('focusin', keepFocusInside);
        return () => {
            document.removeEventListener('focusin', keepFocusInside);
            if (opener && typeof opener.focus === 'function') {
                opener.focus();
            }
        };
    }, []);

    // A mouse-down on the backdrop would move focus to <body>: swallow it.
    const handleBackdropMouseDown = e => {
        if (e.target === e.currentTarget) {
            e.preventDefault();
        }
    };

    const handleKeyDown = e => {
        if (e.key === 'Escape') {
            e.preventDefault();
            onCancel();
            return;
        }

        if (e.key !== 'Tab') {
            return;
        }

        const buttons = [...dialogRef.current.querySelectorAll('button')];
        const first = buttons[0];
        const last = buttons[buttons.length - 1];
        if (e.shiftKey && document.activeElement === first) {
            e.preventDefault();
            last.focus();
        } else if (!e.shiftKey && document.activeElement === last) {
            e.preventDefault();
            first.focus();
        }
    };

    return (
        <div className={styles.mcp_backdrop} onMouseDown={handleBackdropMouseDown}>
            <div ref={dialogRef}
                 role="alertdialog"
                 aria-modal="true"
                 aria-labelledby="mcp-dialog-title"
                 aria-describedby="mcp-dialog-body"
                 className={styles.mcp_dialog}
                 onKeyDown={handleKeyDown}
            >
                <h2 id="mcp-dialog-title" className={styles.mcp_dialogTitle}>{title}</h2>
                <p id="mcp-dialog-body" className={styles.mcp_dialogBody}>{body}</p>
                <div className={styles.mcp_dialogActions}>
                    <button ref={cancelRef} type="button" className={styles.mcp_btn} onClick={onCancel}>{cancelLabel}</button>
                    <button type="button" className={`${styles.mcp_btn} ${styles['mcp_btn--primary']}`} onClick={onConfirm}>{confirmLabel}</button>
                </div>
            </div>
        </div>
    );
};

ConfirmDialog.propTypes = {
    title: PropTypes.string.isRequired,
    body: PropTypes.string.isRequired,
    confirmLabel: PropTypes.string.isRequired,
    cancelLabel: PropTypes.string.isRequired,
    onConfirm: PropTypes.func.isRequired,
    onCancel: PropTypes.func.isRequired
};

export default ConfirmDialog;
