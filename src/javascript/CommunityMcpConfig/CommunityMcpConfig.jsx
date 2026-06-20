import React, {useEffect, useState} from 'react';
import PropTypes from 'prop-types';
import {useApolloClient, useMutation, useQuery} from '@apollo/client';
import {useTranslation} from 'react-i18next';
import {Button, Loader, Typography} from '@jahia/moonstone';
import styles from './CommunityMcpConfig.scss';
import {GET_MUTATION_FIELDS, GET_QUERY_FIELDS, GET_SETTINGS, GET_TYPE_FIELDS, SAVE_SETTINGS} from './CommunityMcpConfig.gql';
import {
    buildChildNodes,
    buildOperationMap,
    isCoveredBySet,
    isExpandableKind,
    mapTypeFields,
    toggleWhitelistPath
} from './operationTree';

export const CommunityMcpConfigAdmin = () => {
    const {t} = useTranslation('jahia-mcp-community-server');
    const apolloClient = useApolloClient();
    const [saveStatus, setSaveStatus] = useState(null);
    const [whitelist, setWhitelist] = useState(new Set());
    const [dirty, setDirty] = useState(false);
    // Schema cache: typeName → FieldInfo[] | 'loading'
    const [typeFields, setTypeFields] = useState({});
    const [expandedWl, setExpandedWl] = useState(new Set());

    const {loading: loadingQueryFields, data: queryFieldsData} = useQuery(GET_QUERY_FIELDS, {fetchPolicy: 'cache-first'});
    const {loading: loadingMutationFields, data: mutationFieldsData} = useQuery(GET_MUTATION_FIELDS, {fetchPolicy: 'cache-first'});
    // Network-only so we always read the server's persisted whitelist on mount.
    const {loading: loadingSettings, data: settingsData} = useQuery(GET_SETTINGS, {fetchPolicy: 'network-only'});

    const [saveSettings, {loading: saving, error: saveError}] = useMutation(SAVE_SETTINGS);

    // Sync server settings into local state via an effect rather than `onCompleted`.
    // `onCompleted` fires on every cache read (including unrelated cache writes) and
    // would clobber unsaved edits already made by the user.
    useEffect(() => {
        if (settingsData?.mcp?.settings) {
            setWhitelist(new Set(settingsData.mcp.settings.whitelist || []));
            setDirty(false);
        }
    }, [settingsData]);

    const operations = buildOperationMap(
        queryFieldsData?.queryFields?.fields,
        mutationFieldsData?.mutationFields?.fields
    );

    const expandNode = async (node, expandedPaths, setExpandedPaths) => {
        if (expandedPaths.has(node.path)) {
            setExpandedPaths(prev => {
                const next = new Set(prev);
                next.delete(node.path);
                return next;
            });
            return;
        }

        setExpandedPaths(prev => new Set([...prev, node.path]));
        if (!node.typeName || typeFields[node.typeName]) {
            return;
        }

        setTypeFields(prev => ({...prev, [node.typeName]: 'loading'}));
        try {
            const result = await apolloClient.query({
                query: GET_TYPE_FIELDS,
                variables: {typeName: node.typeName},
                fetchPolicy: 'cache-first'
            });
            setTypeFields(prev => ({
                ...prev,
                [node.typeName]: mapTypeFields(result.data?.typeFields?.fields)
            }));
        } catch (err) {
            console.error('Failed to load fields for type', node.typeName, err);
            setTypeFields(prev => ({...prev, [node.typeName]: []}));
        }
    };

    const toggle = path => {
        setWhitelist(prev => toggleWhitelistPath(prev, path));
        setDirty(true);
    };

    const selectAll = () => {
        setWhitelist(new Set(operations.map(op => op.name)));
        setDirty(true);
    };

    const unselectAll = () => {
        setWhitelist(new Set());
        setDirty(true);
    };

    const handleSave = async () => {
        setSaveStatus(null);
        try {
            const result = await saveSettings({variables: {whitelist: [...whitelist]}});
            if (result.data?.mcp?.saveSettings) {
                setSaveStatus('success');
                setDirty(false);
            } else {
                setSaveStatus('error');
            }
        } catch (err) {
            console.error('Failed to save MCP settings:', err);
            // Distinguish a transport/network failure from a GraphQL-level error.
            setSaveStatus(err?.networkError ? 'networkError' : 'error');
        }
    };

    useEffect(() => {
        if (saveStatus) {
            const timer = setTimeout(() => setSaveStatus(null), 4000);
            return () => clearTimeout(timer);
        }
    }, [saveStatus]);

    const saveMessage = (() => {
        switch (saveStatus) {
            case 'success': return t('label.saveSuccess');
            case 'networkError': return t('label.saveNetworkError');
            case 'error': return saveError?.message ? t('label.saveErrorDetail', {message: saveError.message}) : t('label.saveError');
            default: return '';
        }
    })();

    if (loadingQueryFields || loadingMutationFields || loadingSettings) {
        return (
            <div className={styles.mcp_loading} role="status" aria-busy="true" aria-live="polite">
                <Loader size="big"/>
                <span className={styles.mcp_visuallyHidden}>{t('label.loading')}</span>
            </div>
        );
    }

    return (
        <div className={styles.mcp_container}>
            <div className={styles.mcp_header}>
                <h2>{t('label.title')}</h2>
            </div>
            <div className={styles.mcp_description}>
                <Typography>{t('label.description')}</Typography>
            </div>

            <div className={styles.mcp_panels}>
                <OperationPanel
                    title={t('label.allowList')}
                    hint={t('label.allowListHint')}
                    emptyHint={t('label.allowListEmpty')}
                    selectAllLabel={t('label.selectAll')}
                    unselectAllLabel={t('label.unselectAll')}
                    queryBadgeLabel={t('label.queryBadge')}
                    mutationBadgeLabel={t('label.mutationBadge')}
                    legendQueryLabel={t('label.legendQuery')}
                    legendMutationLabel={t('label.legendMutation')}
                    coveredLabel={t('label.coveredByAncestor')}
                    loadingFieldsLabel={t('label.loadingFields')}
                    expandLabel={name => t('label.expand', {name})}
                    collapseLabel={name => t('label.collapse', {name})}
                    operations={operations}
                    selected={whitelist}
                    typeFields={typeFields}
                    expandedPaths={expandedWl}
                    styles={styles}
                    onToggle={path => toggle(path)}
                    onSelectAll={selectAll}
                    onUnselectAll={unselectAll}
                    onExpand={node => expandNode(node, expandedWl, setExpandedWl)}
                />
            </div>

            <div className={styles.mcp_actions}>
                <output
                    aria-live="polite"
                    aria-atomic="true"
                    className={
                        saveStatus === 'success' ?
                            `${styles.mcp_alert} ${styles['mcp_alert--success']}` :
                            (saveStatus ? `${styles.mcp_alert} ${styles['mcp_alert--error']}` : styles.mcp_visuallyHidden)
                    }
                >
                    {saveMessage}
                </output>
                <Button
                    label={t('label.save')}
                    variant="primary"
                    isDisabled={saving || !dirty}
                    onClick={handleSave}
                />
            </div>
        </div>
    );
};

const treeNodeShape = {
    name: PropTypes.string.isRequired,
    path: PropTypes.string.isRequired,
    description: PropTypes.string,
    typeName: PropTypes.string,
    typeKind: PropTypes.string,
    isQuery: PropTypes.bool,
    isMutation: PropTypes.bool
};

const TreeNode = ({node, depth, selected, typeFields, expandedPaths, onToggle, onExpand, styles, expandLabel, collapseLabel, queryBadgeLabel, mutationBadgeLabel, coveredLabel, loadingFieldsLabel}) => {
    const isExpandable = isExpandableKind(node.typeKind, depth);
    const isExpanded = expandedPaths.has(node.path);
    const rawChildren = node.typeName ? typeFields[node.typeName] : null;
    const isLoading = rawChildren === 'loading';
    const covered = isCoveredBySet(node.path, selected);
    const directlySelected = selected.has(node.path);
    const coveredByAncestor = covered && !directlySelected;

    const children = isExpanded ? buildChildNodes(node, rawChildren) : null;
    const checkboxLabel = node.description || node.path;

    return (
        <div>
            <div
                className={`${styles.mcp_treeRow}${coveredByAncestor ? ' ' + styles['mcp_treeRow--covered'] : ''}`}
                style={{paddingLeft: `${12 + (depth * 18)}px`}}
            >
                {isExpandable ? (
                    <button
                        className={styles.mcp_expandBtn}
                        type="button"
                        aria-label={isExpanded ? collapseLabel(node.name) : expandLabel(node.name)}
                        aria-expanded={isExpanded}
                        onClick={() => onExpand(node)}
                    >
                        {isExpanded ? '▾' : '▸'}
                    </button>
                ) : (
                    <span className={styles.mcp_expandPlaceholder}/>
                )}
                <input
                    type="checkbox"
                    className={styles.mcp_checkbox}
                    checked={covered}
                    disabled={coveredByAncestor}
                    aria-label={coveredByAncestor ? `${checkboxLabel} (${coveredLabel})` : checkboxLabel}
                    onChange={() => onToggle(node.path)}
                />
                <span className={styles.mcp_operationName}>{node.name}</span>
                {depth === 0 && (
                    <span className={styles.mcp_typeBadges}>
                        {node.isQuery && <span className={`${styles.mcp_badge} ${styles['mcp_badge--query']}`} aria-label={queryBadgeLabel}>Q</span>}
                        {node.isMutation && <span className={`${styles.mcp_badge} ${styles['mcp_badge--mutation']}`} aria-label={mutationBadgeLabel}>M</span>}
                    </span>
                )}
            </div>
            {isExpanded && isLoading && (
                <div
                    className={styles.mcp_treeLoading}
                    role="status"
                    aria-busy="true"
                    style={{paddingLeft: `${12 + ((depth + 1) * 18)}px`}}
                >
                    <span aria-hidden="true">…</span>
                    <span className={styles.mcp_visuallyHidden}>{loadingFieldsLabel}</span>
                </div>
            )}
            {children && children.map(child => (
                <TreeNode
                    key={child.path}
                    node={child}
                    depth={depth + 1}
                    selected={selected}
                    typeFields={typeFields}
                    expandedPaths={expandedPaths}
                    styles={styles}
                    expandLabel={expandLabel}
                    collapseLabel={collapseLabel}
                    queryBadgeLabel={queryBadgeLabel}
                    mutationBadgeLabel={mutationBadgeLabel}
                    coveredLabel={coveredLabel}
                    loadingFieldsLabel={loadingFieldsLabel}
                    onToggle={onToggle}
                    onExpand={onExpand}
                />
            ))}
        </div>
    );
};

TreeNode.propTypes = {
    node: PropTypes.shape(treeNodeShape).isRequired,
    depth: PropTypes.number.isRequired,
    selected: PropTypes.instanceOf(Set).isRequired,
    typeFields: PropTypes.object.isRequired,
    expandedPaths: PropTypes.instanceOf(Set).isRequired,
    onToggle: PropTypes.func.isRequired,
    onExpand: PropTypes.func.isRequired,
    styles: PropTypes.object.isRequired,
    expandLabel: PropTypes.func.isRequired,
    collapseLabel: PropTypes.func.isRequired,
    queryBadgeLabel: PropTypes.string.isRequired,
    mutationBadgeLabel: PropTypes.string.isRequired,
    coveredLabel: PropTypes.string.isRequired,
    loadingFieldsLabel: PropTypes.string.isRequired
};

const OperationPanel = ({title, hint, emptyHint, selectAllLabel, unselectAllLabel, queryBadgeLabel, mutationBadgeLabel, legendQueryLabel, legendMutationLabel, coveredLabel, loadingFieldsLabel, expandLabel, collapseLabel, operations, selected, typeFields, expandedPaths, onToggle, onSelectAll, onUnselectAll, onExpand, styles}) => {
    const titleId = 'mcp-panel-title';
    return (
        <div className={styles.mcp_panel}>
            <div className={styles.mcp_panelHeader}>
                <div className={styles.mcp_panelHeaderTop}>
                    <h3 className={styles.mcp_panelTitle} id={titleId}>{title}</h3>
                    <div className={styles.mcp_panelBulkActions}>
                        <button className={styles.mcp_linkBtn} type="button" onClick={onSelectAll}>{selectAllLabel}</button>
                        <button className={styles.mcp_linkBtn} type="button" onClick={onUnselectAll}>{unselectAllLabel}</button>
                    </div>
                </div>
                <Typography className={styles.mcp_panelHint}>{selected.size === 0 ? emptyHint : hint}</Typography>
                <div className={styles.mcp_badgeLegend}>
                    <span className={styles.mcp_badgeLegendItem}>
                        <span className={`${styles.mcp_badge} ${styles['mcp_badge--query']}`} aria-hidden="true">Q</span>
                        {legendQueryLabel}
                    </span>
                    <span className={styles.mcp_badgeLegendItem}>
                        <span className={`${styles.mcp_badge} ${styles['mcp_badge--mutation']}`} aria-hidden="true">M</span>
                        {legendMutationLabel}
                    </span>
                </div>
            </div>
            <fieldset className={styles.mcp_operationGroup} aria-labelledby={titleId}>
                <legend className={styles.mcp_visuallyHidden}>{title}</legend>
                <div className={styles.mcp_operationList}>
                    {operations.map(op => (
                        <TreeNode
                            key={op.name}
                            node={op}
                            depth={0}
                            selected={selected}
                            typeFields={typeFields}
                            expandedPaths={expandedPaths}
                            styles={styles}
                            expandLabel={expandLabel}
                            collapseLabel={collapseLabel}
                            queryBadgeLabel={queryBadgeLabel}
                            mutationBadgeLabel={mutationBadgeLabel}
                            coveredLabel={coveredLabel}
                            loadingFieldsLabel={loadingFieldsLabel}
                            onToggle={onToggle}
                            onExpand={onExpand}
                        />
                    ))}
                </div>
            </fieldset>
        </div>
    );
};

OperationPanel.propTypes = {
    title: PropTypes.string.isRequired,
    hint: PropTypes.string.isRequired,
    emptyHint: PropTypes.string.isRequired,
    selectAllLabel: PropTypes.string.isRequired,
    unselectAllLabel: PropTypes.string.isRequired,
    queryBadgeLabel: PropTypes.string.isRequired,
    mutationBadgeLabel: PropTypes.string.isRequired,
    legendQueryLabel: PropTypes.string.isRequired,
    legendMutationLabel: PropTypes.string.isRequired,
    coveredLabel: PropTypes.string.isRequired,
    loadingFieldsLabel: PropTypes.string.isRequired,
    expandLabel: PropTypes.func.isRequired,
    collapseLabel: PropTypes.func.isRequired,
    operations: PropTypes.array.isRequired,
    selected: PropTypes.instanceOf(Set).isRequired,
    typeFields: PropTypes.object.isRequired,
    expandedPaths: PropTypes.instanceOf(Set).isRequired,
    onToggle: PropTypes.func.isRequired,
    onSelectAll: PropTypes.func.isRequired,
    onUnselectAll: PropTypes.func.isRequired,
    onExpand: PropTypes.func.isRequired,
    styles: PropTypes.object.isRequired
};

export default CommunityMcpConfigAdmin;
