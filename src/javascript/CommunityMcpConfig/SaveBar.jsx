import React from 'react';
import PropTypes from 'prop-types';
import styles from './CommunityMcpConfig.scss';

// Sticky action bar: unsaved-changes indicator, Discard, Save and the save outcome.
// Save is never `disabled` (focus would be lost when it flips, WCAG 2.4.3); a click without
// changes reports "No changes to save" instead. Messages never time out (WCAG 2.2.3): success
// and info stay until the next edit or until dismissed, errors until dismissed or retried.
export const SaveBar = ({isDirty, hasChangesToDiscard, isSaving, status, onSave, onDiscard, onDismiss, t}) => {
    const isError = status?.kind === 'error';
    const politeText = status && !isError ? status.message : '';
    return (
        <div className={styles.mcp_actions} role="region" aria-label={t('label.saveBar')}>
            <div className={styles.mcp_actionsRow}>
                {isDirty && <span className={styles.mcp_unsaved}>{t('label.unsavedChanges')}</span>}
                <div className={styles.mcp_actionsButtons}>
                    {hasChangesToDiscard && (
                        <button type="button" className={styles.mcp_btn} onClick={onDiscard}>{t('label.discard')}</button>
                    )}
                    <button type="button"
                            id="mcp-save"
                            className={`${styles.mcp_btn} ${styles['mcp_btn--primary']}`}
                            aria-busy={isSaving || undefined}
                            onClick={onSave}
                    >
                        {t('label.save')}
                    </button>
                </div>
            </div>
            <div className={styles.mcp_statusRow}>
                <output aria-live="polite"
                        aria-atomic="true"
                        className={politeText ? `${styles.mcp_alert} ${styles[`mcp_alert--${status.kind}`]}` : styles.mcp_visuallyHidden}
                >
                    {isSaving ? t('label.saving') : politeText}
                </output>
                {isError && (
                    <div role="alert" className={`${styles.mcp_alert} ${styles['mcp_alert--error']}`}>
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
    status: PropTypes.shape({kind: PropTypes.string, message: PropTypes.string, detail: PropTypes.string}),
    onSave: PropTypes.func.isRequired,
    onDiscard: PropTypes.func.isRequired,
    onDismiss: PropTypes.func.isRequired,
    t: PropTypes.func.isRequired
};

export default SaveBar;
