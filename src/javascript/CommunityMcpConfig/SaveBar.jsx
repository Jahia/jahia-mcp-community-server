import React from 'react';
import PropTypes from 'prop-types';
import styles from './CommunityMcpConfig.scss';

// Action bar at the end of the panel: unsaved-changes indicator, Discard, Save and the save
// outcome. It is not sticky: a sticky bar would hide the focused control near the bottom of the
// viewport (WCAG 2.4.12). Save is never `disabled` (focus would be lost when it flips, WCAG 2.4.3);
// a click without changes reports "No changes to save" instead. Messages never time out
// (WCAG 2.2.3): success and info stay until the next edit or until dismissed, errors until
// dismissed or retried. Each status carries an `id`: its text is rendered in a new node, so the
// same message shown twice is announced twice.
export const SaveBar = ({isDirty, hasChangesToDiscard, isSaving, status, onSave, onDiscard, onDismiss, t}) => {
    const isError = status?.kind === 'error';
    const politeText = status && !isError ? status.message : '';
    // "Saving…" is shown as well as announced (A11Y-6).
    const shownKind = isSaving ? 'info' : status?.kind;
    const isOutputShown = isSaving || Boolean(politeText);
    return (
        <div className={styles.mcp_actions} role="region" aria-label={t('label.saveBar')}>
            <div className={styles.mcp_actionsRow}>
                {/* Persistent status container: the indicator appearing is announced (WCAG 4.1.3). */}
                <span role="status" data-testid="mcp-unsaved">
                    {isDirty && <span className={styles.mcp_unsaved}>{t('label.unsavedChanges')}</span>}
                </span>
                <div className={styles.mcp_actionsButtons}>
                    {hasChangesToDiscard && (
                        <button type="button" className={styles.mcp_btn} onClick={onDiscard}>{t('label.discard')}</button>
                    )}
                    <button type="button"
                            id="mcp-save"
                            className={`${styles.mcp_btn} ${styles['mcp_btn--primary']}`}
                            onClick={onSave}
                    >
                        {t('label.save')}
                    </button>
                </div>
            </div>
            <div className={styles.mcp_statusRow}>
                <output aria-live="polite"
                        aria-atomic="true"
                        className={isOutputShown ? `${styles.mcp_alert} ${styles[`mcp_alert--${shownKind}`]}` : styles.mcp_visuallyHidden}
                >
                    {isSaving ? t('label.saving') : politeText && <span key={status.id}>{politeText}</span>}
                </output>
                {isError && (
                    <div key={status.id} role="alert" className={`${styles.mcp_alert} ${styles['mcp_alert--error']}`}>
                        <strong>{t('label.errorPrefix')}</strong> {status.message}
                        {status.detail && <> <span lang="en" className={styles.mcp_errorDetail}>{status.detail}</span></>}
                    </div>
                )}
                {status && (
                    <button type="button" className={`${styles.mcp_btn} ${styles['mcp_btn--small']}`} onClick={onDismiss}>
                        {t('label.dismiss')}
                    </button>
                )}
            </div>
        </div>
    );
};

SaveBar.propTypes = {
    isDirty: PropTypes.bool.isRequired,
    hasChangesToDiscard: PropTypes.bool.isRequired,
    isSaving: PropTypes.bool.isRequired,
    status: PropTypes.shape({id: PropTypes.number, kind: PropTypes.string, message: PropTypes.string, detail: PropTypes.string}),
    onSave: PropTypes.func.isRequired,
    onDiscard: PropTypes.func.isRequired,
    onDismiss: PropTypes.func.isRequired,
    t: PropTypes.func.isRequired
};

export default SaveBar;
