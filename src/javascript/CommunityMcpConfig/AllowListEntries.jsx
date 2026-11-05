import React from 'react';
import PropTypes from 'prop-types';
import styles from './CommunityMcpConfig.scss';

const RemoveButton = ({entry, onRemove, t}) => (
    <button type="button" className={`${styles.mcp_btn} ${styles['mcp_btn--small']}`} onClick={() => onRemove(entry)}>
        {t('label.remove')}<span className={styles.mcp_visuallyHidden}> {entry}</span>
    </button>
);

RemoveButton.propTypes = {
    entry: PropTypes.string.isRequired,
    onRemove: PropTypes.func.isRequired,
    t: PropTypes.func.isRequired
};

// Entries the tree cannot show (root operation missing from the schema) or that the server
// always refuses. They would otherwise be saved again silently on every Save.
export const ProblemEntries = ({problems, onRemove, t}) => {
    if (problems.length === 0) {
        return null;
    }

    return (
        <section className={`${styles.mcp_alert} ${styles['mcp_alert--warning']}`} aria-labelledby="mcp-problems-title">
            <h4 id="mcp-problems-title" className={styles.mcp_alertTitle}>{t('label.problemEntriesTitle')}</h4>
            <p className={styles.mcp_alertText}>{t('label.problemEntriesHint')}</p>
            <ul className={styles.mcp_entryList}>
                {problems.map(({entry, reason}) => (
                    <li key={entry} className={styles.mcp_entry}>
                        <code lang="en" className={styles.mcp_entryCode}>{entry}</code>
                        <span className={styles.mcp_entryReason}>
                            {t(reason === 'locked' ? 'label.problemLocked' : 'label.problemUnknown')}
                        </span>
                        <RemoveButton entry={entry} t={t} onRemove={onRemove}/>
                    </li>
                ))}
            </ul>
        </section>
    );
};

ProblemEntries.propTypes = {
    problems: PropTypes.arrayOf(PropTypes.shape({entry: PropTypes.string, reason: PropTypes.string})).isRequired,
    onRemove: PropTypes.func.isRequired,
    t: PropTypes.func.isRequired
};

// The raw allow list, as it will be saved: lets an administrator audit every entry, including
// fields deeper than the tree shows, and remove any of them.
export const AllEntries = ({entries, onRemove, t}) => (
    <details className={styles.mcp_details}>
        <summary className={styles.mcp_summary}>{t('label.allEntriesSummary', {count: entries.length})}</summary>
        {entries.length === 0 ? <p className={styles.mcp_detailsText}>{t('label.noEntries')}</p> : (
            <ul className={styles.mcp_entryList}>
                {entries.map(entry => (
                    <li key={entry} className={styles.mcp_entry}>
                        <code lang="en" className={styles.mcp_entryCode}>{entry}</code>
                        <RemoveButton entry={entry} t={t} onRemove={onRemove}/>
                    </li>
                ))}
            </ul>
        )}
    </details>
);

AllEntries.propTypes = {
    entries: PropTypes.arrayOf(PropTypes.string).isRequired,
    onRemove: PropTypes.func.isRequired,
    t: PropTypes.func.isRequired
};
