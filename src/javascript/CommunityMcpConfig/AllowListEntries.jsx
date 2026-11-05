import React from 'react';
import PropTypes from 'prop-types';
import styles from './CommunityMcpConfig.scss';

// The entry focus should move to once `entry` is removed: the next one in the same list, or the
// previous one at the end (WCAG 2.4.3). Read from the list as rendered.
const neighbourOf = button => {
    const li = button.closest('li');
    const sibling = li?.nextElementSibling || li?.previousElementSibling;
    return sibling?.dataset.entry || null;
};

const RemoveButton = ({entry, list, onRemove, t}) => (
    <button type="button"
            className={`${styles.mcp_btn} ${styles['mcp_btn--small']}`}
            onClick={e => onRemove(entry, {list, neighbour: neighbourOf(e.currentTarget)})}
    >
        {t('label.remove')}<span className={styles.mcp_visuallyHidden}> {entry}</span>
    </button>
);

RemoveButton.propTypes = {
    entry: PropTypes.string.isRequired,
    list: PropTypes.string.isRequired,
    onRemove: PropTypes.func.isRequired,
    t: PropTypes.func.isRequired
};

const REASON_KEYS = {
    invalid: 'label.problemInvalid',
    locked: 'label.problemLocked',
    lockedConfiguration: 'label.problemLockedConfiguration',
    unknown: 'label.problemUnknown'
};

// Entries the tree cannot show (root operation missing from the schema), that the server always
// refuses (locked) or ignores (invalid). They stay in the list until removed here.
export const ProblemEntries = ({problems, onRemove, t}) => {
    if (problems.length === 0) {
        return null;
    }

    return (
        <section className={`${styles.mcp_alert} ${styles['mcp_alert--warning']}`} aria-labelledby="mcp-problems-title">
            <h4 id="mcp-problems-title" className={styles.mcp_alertTitle}>{t('label.problemEntriesTitle')}</h4>
            <p className={styles.mcp_alertText}>{t('label.problemEntriesHint')}</p>
            <ul className={styles.mcp_entryList} data-list="problems">
                {problems.map(({entry, reason}) => (
                    <li key={entry} className={styles.mcp_entry} data-entry={entry}>
                        <code lang="en" className={styles.mcp_entryCode}>{entry}</code>
                        <span className={styles.mcp_entryReason}>{t(REASON_KEYS[reason])}</span>
                        <RemoveButton entry={entry} list="problems" t={t} onRemove={onRemove}/>
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
        <summary id="mcp-all-entries" className={styles.mcp_summary}>{t('label.allEntriesSummary', {count: entries.length})}</summary>
        {entries.length === 0 ? <p className={styles.mcp_detailsText}>{t('label.noEntries')}</p> : (
            <ul className={styles.mcp_entryList} data-list="all">
                {entries.map(entry => (
                    <li key={entry} className={styles.mcp_entry} data-entry={entry}>
                        <code lang="en" className={styles.mcp_entryCode}>{entry}</code>
                        <RemoveButton entry={entry} list="all" t={t} onRemove={onRemove}/>
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
