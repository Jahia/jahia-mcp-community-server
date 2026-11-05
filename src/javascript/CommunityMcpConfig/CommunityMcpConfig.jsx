import React, {useEffect, useRef, useState} from 'react';
import {useApolloClient, useMutation, useQuery} from '@apollo/client';
import {Loader} from '@jahia/moonstone';
import styles from './CommunityMcpConfig.scss';
import {GET_MUTATION_FIELDS, GET_QUERY_FIELDS, GET_SETTINGS, GET_TYPE_FIELDS, SAVE_SETTINGS} from './CommunityMcpConfig.gql';
import {
    allowAllOfType,
    buildOperationMap,
    clearAllOfType,
    countEntriesOfType,
    findBlockingEntries,
    findProblemEntries,
    hasLoadedMatch,
    mapTypeFields,
    matchesFilter,
    normalizeWhitelist,
    policyOf,
    removeEntry,
    setsEqual,
    toggleWhitelistPath
} from './operationTree';
import {isReadOnlyProtected} from './policyRules';
import {OperationTree} from './OperationTree';
import {ConfirmDialog} from './ConfirmDialog';
import {AllEntries, ProblemEntries} from './AllowListEntries';
import {SaveBar} from './SaveBar';
import {isFocusLost, LiveRegion, useAnnouncer, useFocusRecovery} from './a11y';
import {useMcpTranslation} from './i18n';

const EMPTY = new Set();

// Turn a failed save into a message: the server's own reason when it gave one (UX-6).
const saveFailure = (err, t) => {
    if (err?.networkError) {
        return {kind: 'error', message: t('label.saveNetworkError')};
    }

    const detail = err?.graphQLErrors?.[0]?.message || err?.message;
    return detail ?
        {kind: 'error', message: t('label.saveErrorServer'), detail} :
        {kind: 'error', message: t('label.saveError')};
};

// Focus targets used when the focused control disappears (WCAG 2.4.3).
const focusPanelHeading = root => root.querySelector('#mcp-panel-title');
const focusSave = root => root.querySelector('#mcp-save');
const focusAfterRemove = ({list, neighbour}) => root => {
    const next = [...root.querySelectorAll(`[data-list="${list}"] [data-entry]`)]
        .find(el => el.dataset.entry === neighbour);
    return next?.querySelector('button') ||
        (list === 'all' && root.querySelector('#mcp-all-entries')) ||
        focusPanelHeading(root);
};

const useUnsavedChangesGuard = isDirty => {
    useEffect(() => {
        if (!isDirty) {
            return undefined;
        }

        const guard = e => {
            e.preventDefault();
            e.returnValue = '';
        };

        window.addEventListener('beforeunload', guard);
        return () => window.removeEventListener('beforeunload', guard);
    }, [isDirty]);
};

const Help = ({t}) => (
    <details className={styles.mcp_details}>
        <summary className={styles.mcp_summary}>{t('label.helpSummary')}</summary>
        <div className={styles.mcp_detailsText}>
            <p>{t('label.helpEntries')}</p>
            <p>{t('label.helpDefault')}</p>
            <p>{t('label.helpAllowAll')}</p>
            <p>{t('label.helpKeyboard')}</p>
        </div>
    </details>
);

// The policy of the edited list, and, while it is not saved, the policy the server still applies
// (UX-4): an unsaved restriction must never read as if it were already in force.
const PolicyBanner = ({policy, savedPolicy, t}) => {
    const alertClass = kind => `${styles.mcp_alert} ${styles[`mcp_alert--${kind}`]}`;
    if (!savedPolicy) {
        return <p className={alertClass(policy.kind)}>{t(policy.key)}</p>;
    }

    return (
        <>
            <p className={alertClass(policy.kind)}><strong>{t('label.policyPendingPrefix')}</strong> {t(policy.key)}</p>
            <p className={alertClass(savedPolicy.kind)}><strong>{t('label.policyInForce')}</strong> {t(savedPolicy.key)}</p>
        </>
    );
};

const OperationGroup = ({id, heading, count, note, roots, actions, treeProps}) => (
    <section className={styles.mcp_group} aria-labelledby={`mcp-group-${id}`}>
        <div className={styles.mcp_groupHeader}>
            <h4 className={styles.mcp_groupHeading} id={`mcp-group-${id}`}>
                <span id={`mcp-group-${id}-label`}>{heading}</span>
                {' '}<span className={styles.mcp_groupCount}>{count}</span>
            </h4>
            <div className={styles.mcp_groupActions}>
                {actions.map(action => (
                    <button key={action.label} type="button" className={`${styles.mcp_btn} ${styles['mcp_btn--small']}`} onClick={action.onClick}>
                        {action.label}
                    </button>
                ))}
            </div>
        </div>
        <p className={styles.mcp_groupNote}>{note}</p>
        {roots.length > 0 && <OperationTree roots={roots} labelledBy={`mcp-group-${id}-label`} {...treeProps}/>}
    </section>
);

export const CommunityMcpConfigAdmin = () => {
    const t = useMcpTranslation();
    const rootRef = useRef(null);
    const requestFocus = useFocusRecovery(rootRef);
    const [announcement, announce] = useAnnouncer();
    const statusSeq = useRef(0);
    const apolloClient = useApolloClient();

    const queryFieldsResult = useQuery(GET_QUERY_FIELDS, {fetchPolicy: 'cache-first'});
    const mutationFieldsResult = useQuery(GET_MUTATION_FIELDS, {fetchPolicy: 'cache-first'});
    // Network-only so we always read the server's persisted allow list on mount.
    const settingsResult = useQuery(GET_SETTINGS, {fetchPolicy: 'network-only'});
    const [saveSettings] = useMutation(SAVE_SETTINGS);

    const [whitelist, setWhitelist] = useState(EMPTY);
    const [saved, setSaved] = useState(EMPTY);
    const [isMigrated, setMigrated] = useState(false);
    const [isSaving, setSaving] = useState(false);
    const [status, setStatusState] = useState(null);
    const [typeFields, setTypeFields] = useState({});
    const [expanded, setExpanded] = useState(EMPTY);
    const [filterInput, setFilterInput] = useState('');
    const [confirm, setConfirm] = useState(null);

    const setStatus = next => {
        statusSeq.current += 1;
        setStatusState(next && {...next, id: statusSeq.current});
    };

    const settingsData = settingsResult.data;
    // The allow list is stored per node: in a cluster, saving here changes this node only (UX-2).
    const isClustered = Boolean(settingsData?.communityMcp?.settings?.clustered);
    // Read by the sync effect below without re-running it on every edit.
    const editState = useRef({isDirty: false, saved: EMPTY});
    // Sync server settings into local state via an effect rather than `onCompleted`, which
    // fires on unrelated cache writes and would clobber unsaved edits.
    useEffect(() => {
        if (!settingsData?.communityMcp?.settings) {
            return;
        }

        // Legacy unprefixed entries are shown as the query-only entries the server enforces;
        // if any were rewritten, the form starts dirty so the new form can be saved. Entries
        // the server ignores are kept as written and listed as problems (never dropped).
        const {entries, migrated} = normalizeWhitelist(settingsData.communityMcp.settings.whitelist);
        const {isDirty: hasPendingEdits, saved: current} = editState.current;
        if (hasPendingEdits) {
            // A refetch or cache reset by the shell must never overwrite pending edits: keep them,
            // compare them with what the server holds now, and say that it changed.
            if (!setsEqual(entries, current)) {
                setSaved(entries);
                setStatus({kind: 'info', message: t('label.serverChanged')});
            }

            return;
        }

        setWhitelist(entries);
        setSaved(entries);
        setMigrated(migrated);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [settingsData]);

    const isLoading = queryFieldsResult.loading || mutationFieldsResult.loading || settingsResult.loading;
    const failedResults = [settingsResult, queryFieldsResult, mutationFieldsResult].filter(r => r.error);
    let loadErrorKey = null;
    if (settingsResult.error) {
        loadErrorKey = 'label.loadError';
    } else if (failedResults.length > 0) {
        loadErrorKey = 'label.schemaLoadError';
    }

    // "Try again" disappears while loading and once the data arrives. When the retry settles,
    // focus goes to the panel heading, or back to "Try again" if it failed again after a loading
    // state removed it (WCAG 2.4.3). Focus left on a control that still exists is never moved.
    const retry = useRef(null);
    useEffect(() => {
        if (!retry.current) {
            return;
        }

        if (isLoading) {
            retry.current.sawLoading = true;
            return;
        }

        if (loadErrorKey && !retry.current.sawLoading) {
            return;
        }

        retry.current = null;
        const root = rootRef.current;
        const target = loadErrorKey ? root.querySelector('#mcp-retry') : focusPanelHeading(root);
        if (target && isFocusLost()) {
            target.focus();
        }
    });

    const wasLoading = useRef(isLoading);
    useEffect(() => {
        if (wasLoading.current && !isLoading && !loadErrorKey) {
            announce(t('label.loaded'));
        }

        wasLoading.current = isLoading;
    }, [isLoading, loadErrorKey, t, announce]);

    const filter = filterInput.trim();
    const isDirty = isMigrated || !setsEqual(whitelist, saved);
    editState.current = {isDirty, saved};
    useUnsavedChangesGuard(isDirty);

    const operations = buildOperationMap(
        queryFieldsResult.data?.queryFields?.fields,
        mutationFieldsResult.data?.mutationFields?.fields
    );

    const isVisibleRoot = op => !filter || matchesFilter(op.path, filter) || hasLoadedMatch(op, typeFields, filter, 0);
    const queryRoots = operations.filter(op => op.isQuery);
    const mutationRoots = operations.filter(op => op.isMutation);
    const visibleQueries = queryRoots.filter(isVisibleRoot);
    const visibleMutations = mutationRoots.filter(isVisibleRoot);
    const visibleCount = visibleQueries.length + visibleMutations.length;

    useEffect(() => {
        if (filter) {
            announce(t('label.filterResults', {count: visibleCount}));
        }
    }, [filter, visibleCount, t, announce]);

    // Apply a new list. A change of policy is announced once, with the action's own message.
    const applyEdit = (next, {message, focus} = {}) => {
        const before = policyOf(whitelist).key;
        const after = policyOf(next).key;
        setWhitelist(next);
        setStatus(null);
        if (focus) {
            requestFocus(focus);
        }

        const parts = [message, before !== after && t(after)].filter(Boolean);
        if (parts.length > 0) {
            announce(parts.join(' '));
        }
    };

    const loadTypeFields = async node => {
        setTypeFields(prev => ({...prev, [node.typeName]: 'loading'}));
        try {
            const result = await apolloClient.query({
                query: GET_TYPE_FIELDS,
                variables: {typeName: node.typeName},
                fetchPolicy: 'cache-first'
            });
            const fields = mapTypeFields(result.data?.typeFields?.fields);
            setTypeFields(prev => ({...prev, [node.typeName]: fields}));
            announce(t('label.fieldsLoaded', {count: fields.length, name: node.name}));
        } catch (err) {
            console.error('Failed to load fields for type', node.typeName, err);
            // Collapse and remember the failure: expanding again retries (UX-9).
            setTypeFields(prev => ({...prev, [node.typeName]: 'error'}));
            setExpanded(prev => new Set([...prev].filter(p => p !== node.path)));
        }
    };

    const setNodeExpanded = (node, isOpen) => {
        setExpanded(prev => new Set(isOpen ? [...prev, node.path] : [...prev].filter(p => p !== node.path)));
        const cached = node.typeName ? typeFields[node.typeName] : 'none';
        if (isOpen && (cached === undefined || cached === 'error')) {
            loadTypeFields(node);
        }
    };

    const askConfirm = (prefix, action) => setConfirm({
        title: t(`label.${prefix}Title`),
        body: t(`label.${prefix}Body`),
        confirmLabel: t(`label.${prefix}Action`),
        action
    });

    // One rule for every edit: a change that EMPTIES a non-empty list restores the read-only
    // default, which allows every query (wider access), so it is always confirmed first, whatever
    // the control (Clear, Clear queries/mutations, unchecking the last item, removing the last
    // entry). Once confirmed, focus goes to the panel heading if the control is gone.
    const edit = (update, options = {}) => {
        const next = update(whitelist);
        if (next.size === 0 && whitelist.size > 0) {
            askConfirm('confirmClear', () => applyEdit(next, {...options, focus: options.focus || focusPanelHeading}));
        } else {
            applyEdit(next, options);
        }
    };

    // Checking the admin query namespace (or a field under it) exposes configuration values,
    // secrets included: a deliberate action, confirmed each time (UX-1). Unchecking is not.
    const togglePath = path => {
        const isAdding = !whitelist.has(path);
        if (isAdding && isReadOnlyProtected(path)) {
            askConfirm('confirmAllowAdmin', () => edit(s => toggleWhitelistPath(s, path)));
        } else {
            edit(s => toggleWhitelistPath(s, path));
        }
    };

    const clearAll = () => edit(() => EMPTY);
    const allowAllMutations = () => askConfirm('confirmAllowMutations', () => edit(s => allowAllOfType(s, operations, false)));
    const clearQueries = () => {
        const next = clearAllOfType(whitelist, true);
        if (next.size > 0 && countEntriesOfType(whitelist, true) > 0 && countEntriesOfType(next, false) > 0) {
            // Narrowing, but every query becomes refused: confirm as well.
            askConfirm('confirmClearQueries', () => applyEdit(next));
        } else {
            edit(() => next);
        }
    };

    const removeOne = (entry, place) => edit(
        s => removeEntry(s, entry),
        {message: t('label.entryRemoved', {entry}), focus: focusAfterRemove(place)}
    );

    const handleSave = async () => {
        if (isSaving) {
            return;
        }

        if (!isDirty) {
            setStatus({kind: 'info', message: t('label.noChanges')});
            return;
        }

        // The server refuses malformed and locked entries: say so before trying, and point to the
        // list where they can be removed.
        const blocking = findBlockingEntries(whitelist);
        if (blocking.length > 0) {
            setStatus({kind: 'error', message: t('label.saveBlocked', {entries: blocking.join(', ')})});
            return;
        }

        const toSave = whitelist;
        setSaving(true);
        setStatus(null);
        try {
            // Default errorPolicy ('none'): a server rejection is thrown, handled in catch.
            const result = await saveSettings({variables: {whitelist: [...toSave]}});
            if (result.data?.communityMcp?.saveSettings) {
                setSaved(toSave);
                setMigrated(false);
                setStatus({kind: 'success', message: t(isClustered ? 'label.saveSuccessCluster' : 'label.saveSuccess')});
            } else {
                setStatus({kind: 'error', message: t('label.saveError')});
            }
        } catch (err) {
            console.error('Failed to save MCP settings:', err);
            setStatus(saveFailure(err, t));
        } finally {
            setSaving(false);
        }
    };

    // Restores what the server holds: never confirmed, it cannot widen access beyond it.
    const discard = () => applyEdit(saved, {message: t('label.discarded'), focus: focusSave});

    const dismiss = () => {
        setStatus(null);
        requestFocus(focusSave);
    };

    // A failed refetch is shown from the query's own error state, so its rejection is only logged.
    const retryLoad = () => {
        retry.current = {sawLoading: false};
        failedResults.forEach(r => {
            Promise.resolve(r.refetch?.()).catch(err => console.error('Failed to reload MCP settings:', err));
        });
    };

    const renderBody = () => {
        if (isLoading) {
            return (
                <div className={styles.mcp_loading}>
                    <span className={styles.mcp_spinner} aria-hidden="true"><Loader size="big"/></span>
                    <span className={styles.mcp_loadingText}>{t('label.loading')}</span>
                </div>
            );
        }

        if (loadErrorKey) {
            return (
                <div role="alert" className={`${styles.mcp_alert} ${styles['mcp_alert--error']}`}>
                    <p className={styles.mcp_alertText}><strong>{t('label.errorPrefix')}</strong> {t(loadErrorKey)}</p>
                    <button id="mcp-retry"
                            type="button"
                            className={`${styles.mcp_btn} ${styles['mcp_btn--small']}`}
                            onClick={retryLoad}
                    >
                        {t('label.retry')}
                    </button>
                </div>
            );
        }

        const policy = policyOf(whitelist);
        const hasPendingEdits = !setsEqual(whitelist, saved);
        const treeProps = {
            selected: whitelist, typeFields, expanded, filter, t,
            onToggle: togglePath,
            onSetExpanded: setNodeExpanded,
            onAnnounce: announce
        };
        const queryCount = countEntriesOfType(whitelist, true);
        const mutationCount = countEntriesOfType(whitelist, false);

        return (
            <>
                <Help t={t}/>
                <section className={styles.mcp_panel} aria-labelledby="mcp-panel-title">
                    <div className={styles.mcp_panelHeader}>
                        <h3 className={styles.mcp_panelTitle} id="mcp-panel-title" tabIndex={-1}>{t('label.allowList')}</h3>
                        {whitelist.size > 0 && (
                            <button type="button" className={`${styles.mcp_btn} ${styles['mcp_btn--small']}`} onClick={clearAll}>
                                {t('label.clearAll')}
                            </button>
                        )}
                    </div>
                    {/* Not a live region: a change of policy is announced once by applyEdit. */}
                    <PolicyBanner policy={policy} savedPolicy={hasPendingEdits ? policyOf(saved) : null} t={t}/>
                    {isClustered && <p className={`${styles.mcp_alert} ${styles['mcp_alert--warning']}`}>{t('label.clusterNote')}</p>}
                    {isMigrated && <p className={`${styles.mcp_alert} ${styles['mcp_alert--info']}`}>{t('label.migrated')}</p>}
                    <ProblemEntries problems={findProblemEntries(whitelist, operations)} t={t} onRemove={removeOne}/>
                    <div className={styles.mcp_filter}>
                        <label htmlFor="mcp-filter" className={styles.mcp_filterLabel}>{t('label.filterLabel')}</label>
                        <input id="mcp-filter"
                               type="search"
                               className={styles.mcp_filterInput}
                               value={filterInput}
                               aria-describedby="mcp-filter-hint"
                               onChange={e => setFilterInput(e.target.value)}
                        />
                        <span id="mcp-filter-hint" className={styles.mcp_hint}>{t('label.filterHint')}</span>
                    </div>
                    {filter && visibleCount === 0 && <p className={styles.mcp_hint}>{t('label.filterNoMatch', {filter})}</p>}
                    {queryRoots.length > 0 && (
                        <OperationGroup id="queries"
                                        heading={t('label.queriesHeading')}
                                        count={t('label.allowedCount', {count: queryCount})}
                                        note={t(whitelist.size === 0 ? 'label.queriesNoteDefault' : 'label.queriesNoteRestricted')}
                                        roots={visibleQueries}
                                        actions={[
                                            {label: t('label.allowAllQueries'), onClick: () => edit(s => allowAllOfType(s, operations, true))},
                                            {label: t('label.clearQueries'), onClick: clearQueries}
                                        ]}
                                        treeProps={treeProps}/>
                    )}
                    {mutationRoots.length > 0 && (
                        <OperationGroup id="mutations"
                                        heading={t('label.mutationsHeading')}
                                        count={t('label.allowedCount', {count: mutationCount})}
                                        note={t('label.mutationsNote')}
                                        roots={visibleMutations}
                                        actions={[
                                            {label: t('label.allowAllMutations'), onClick: allowAllMutations},
                                            {label: t('label.clearMutations'), onClick: () => edit(s => clearAllOfType(s, false))}
                                        ]}
                                        treeProps={treeProps}/>
                    )}
                    <AllEntries entries={[...whitelist].sort()} t={t} onRemove={removeOne}/>
                </section>
                <SaveBar isDirty={isDirty}
                         hasChangesToDiscard={hasPendingEdits}
                         isSaving={isSaving}
                         status={status}
                         t={t}
                         onSave={handleSave}
                         onDiscard={discard}
                         onDismiss={dismiss}/>
            </>
        );
    };

    return (
        <section ref={rootRef} className={styles.mcp_container} aria-labelledby="mcp-title">
            <LiveRegion message={announcement} className={styles.mcp_visuallyHidden}/>
            <h2 id="mcp-title" className={styles.mcp_title}>{t('label.title')}</h2>
            <p className={styles.mcp_description}>{t('label.description')}</p>
            {renderBody()}
            {confirm && (
                <ConfirmDialog title={confirm.title}
                               body={confirm.body}
                               confirmLabel={confirm.confirmLabel}
                               cancelLabel={t('label.cancel')}
                               onConfirm={() => {
                                   setConfirm(null);
                                   confirm.action();
                               }}
                               onCancel={() => setConfirm(null)}/>
            )}
        </section>
    );
};

export default CommunityMcpConfigAdmin;
