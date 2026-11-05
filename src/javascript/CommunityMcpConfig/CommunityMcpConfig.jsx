import React, {useEffect, useRef, useState} from 'react';
import {useApolloClient, useMutation, useQuery} from '@apollo/client';
import {useTranslation} from 'react-i18next';
import {Loader} from '@jahia/moonstone';
import styles from './CommunityMcpConfig.scss';
import {GET_MUTATION_FIELDS, GET_QUERY_FIELDS, GET_SETTINGS, GET_TYPE_FIELDS, SAVE_SETTINGS} from './CommunityMcpConfig.gql';
import {
    allowAllOfType,
    buildOperationMap,
    clearAllOfType,
    countEntriesOfType,
    findProblemEntries,
    hasLoadedMatch,
    mapTypeFields,
    matchesFilter,
    normalizeWhitelist,
    removeEntry,
    setsEqual,
    toggleWhitelistPath
} from './operationTree';
import {OperationTree} from './OperationTree';
import {ConfirmDialog} from './ConfirmDialog';
import {AllEntries, ProblemEntries} from './AllowListEntries';
import {SaveBar} from './SaveBar';

const EMPTY = new Set();

// Turn a failed save into a message: the server's own reason when it gave one (UX-6).
const saveFailure = (err, t) => {
    if (err?.networkError) {
        return {kind: 'error', message: t('label.saveNetworkError')};
    }

    const detail = err?.graphQLErrors?.[0]?.message || err?.errors?.[0]?.message || err?.message;
    return detail ?
        {kind: 'error', message: t('label.saveErrorServer'), detail} :
        {kind: 'error', message: t('label.saveError')};
};

const policyOf = whitelist => {
    if (whitelist.size === 0) {
        return {kind: 'info', key: 'label.policyReadOnly'};
    }

    return countEntriesOfType(whitelist, true) === 0 ?
        {kind: 'warning', key: 'label.policyNoQuery'} :
        {kind: 'info', key: 'label.policyRestricted'};
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
    const {t} = useTranslation('jahia-mcp-community-server');
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
    const [status, setStatus] = useState(null);
    const [announcement, setAnnouncement] = useState('');
    const [typeFields, setTypeFields] = useState({});
    const [expanded, setExpanded] = useState(EMPTY);
    const [filterInput, setFilterInput] = useState('');
    const [confirm, setConfirm] = useState(null);

    const settingsData = settingsResult.data;
    // Sync server settings into local state via an effect rather than `onCompleted`, which
    // fires on unrelated cache writes and would clobber unsaved edits.
    useEffect(() => {
        if (settingsData?.communityMcp?.settings) {
            // Legacy unprefixed entries are shown as the query-only entries the server enforces;
            // if any were rewritten, the form starts dirty so the new form can be saved.
            const {entries, migrated} = normalizeWhitelist(settingsData.communityMcp.settings.whitelist);
            setWhitelist(entries);
            setSaved(entries);
            setMigrated(migrated);
        }
    }, [settingsData]);

    const isLoading = queryFieldsResult.loading || mutationFieldsResult.loading || settingsResult.loading;
    const failedResults = [settingsResult, queryFieldsResult, mutationFieldsResult].filter(r => r.error);
    let loadErrorKey = null;
    if (settingsResult.error) {
        loadErrorKey = 'label.loadError';
    } else if (failedResults.length > 0) {
        loadErrorKey = 'label.schemaLoadError';
    }

    const wasLoading = useRef(isLoading);
    useEffect(() => {
        if (wasLoading.current && !isLoading && !loadErrorKey) {
            setAnnouncement(t('label.loaded'));
        }

        wasLoading.current = isLoading;
    }, [isLoading, loadErrorKey, t]);

    const filter = filterInput.trim();
    const isDirty = isMigrated || !setsEqual(whitelist, saved);
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
            setAnnouncement(t('label.filterResults', {count: visibleCount}));
        }
    }, [filter, visibleCount, t]);

    const edit = update => {
        setWhitelist(update);
        setStatus(null);
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
            setAnnouncement(t('label.fieldsLoaded', {count: fields.length, name: node.name}));
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

    const clearAll = () => askConfirm('confirmClear', () => edit(() => EMPTY));
    const allowAllMutations = () => askConfirm('confirmAllowMutations', () => edit(s => allowAllOfType(s, operations, false)));
    const clearQueries = () => {
        const doClear = () => edit(s => clearAllOfType(s, true));
        if (countEntriesOfType(whitelist, false) > 0) {
            askConfirm('confirmClearQueries', doClear);
        } else {
            doClear();
        }
    };

    const removeOne = entry => {
        edit(s => removeEntry(s, entry));
        setAnnouncement(t('label.entryRemoved', {entry}));
    };

    const handleSave = async () => {
        if (isSaving) {
            return;
        }

        if (!isDirty) {
            setStatus({kind: 'info', message: t('label.noChanges')});
            return;
        }

        const toSave = whitelist;
        setSaving(true);
        setStatus(null);
        try {
            const result = await saveSettings({variables: {whitelist: [...toSave]}});
            if (result.errors?.length) {
                // With errorPolicy 'all' or 'ignore' the server's rejection arrives in the result, not as a throw
                setStatus(saveFailure(result, t));
            } else if (result.data?.communityMcp?.saveSettings) {
                setSaved(toSave);
                setMigrated(false);
                setStatus({kind: 'success', message: t('label.saveSuccess')});
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

    const discard = () => {
        edit(() => saved);
        setAnnouncement(t('label.discarded'));
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
                    <button type="button"
                            className={`${styles.mcp_btn} ${styles['mcp_btn--small']}`}
                            onClick={() => failedResults.forEach(r => r.refetch && r.refetch())}
                    >
                        {t('label.retry')}
                    </button>
                </div>
            );
        }

        const policy = policyOf(whitelist);
        const treeProps = {
            selected: whitelist, typeFields, expanded, filter, t,
            onToggle: path => edit(s => toggleWhitelistPath(s, path)),
            onSetExpanded: setNodeExpanded,
            onAnnounce: setAnnouncement
        };
        const queryCount = countEntriesOfType(whitelist, true);
        const mutationCount = countEntriesOfType(whitelist, false);

        return (
            <>
                <Help t={t}/>
                <section className={styles.mcp_panel} aria-labelledby="mcp-panel-title">
                    <div className={styles.mcp_panelHeader}>
                        <h3 className={styles.mcp_panelTitle} id="mcp-panel-title">{t('label.allowList')}</h3>
                        {whitelist.size > 0 && (
                            <button type="button" className={`${styles.mcp_btn} ${styles['mcp_btn--small']}`} onClick={clearAll}>
                                {t('label.clearAll')}
                            </button>
                        )}
                    </div>
                    <div role="status" className={`${styles.mcp_alert} ${styles[`mcp_alert--${policy.kind}`]}`}>
                        {t(policy.key)}
                    </div>
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
                         hasChangesToDiscard={!setsEqual(whitelist, saved)}
                         isSaving={isSaving}
                         status={status}
                         t={t}
                         onSave={handleSave}
                         onDiscard={discard}
                         onDismiss={() => setStatus(null)}/>
            </>
        );
    };

    return (
        <section className={styles.mcp_container} aria-labelledby="mcp-title">
            {/* Persistent polite live region: present before any message is written to it. */}
            <div className={styles.mcp_visuallyHidden} role="status" aria-live="polite">{announcement}</div>
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
