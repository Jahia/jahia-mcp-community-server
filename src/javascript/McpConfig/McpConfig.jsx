import React, {useEffect, useState} from 'react';
import {useApolloClient, useMutation, useQuery} from '@apollo/client';
import {useTranslation} from 'react-i18next';
import {Button, Loader, Typography} from '@jahia/moonstone';
import styles from './McpConfig.scss';
import {GET_MUTATION_FIELDS, GET_QUERY_FIELDS, GET_SETTINGS, GET_TYPE_FIELDS, SAVE_SETTINGS} from './McpConfig.gql';

const MAX_TREE_DEPTH = 5;

// Unwrap NON_NULL / LIST wrappers to reach the named type
const getNamedType = typeObj => {
    if (!typeObj) return null;
    if (typeObj.name) return typeObj;
    return getNamedType(typeObj.ofType);
};

const buildOperationMap = (queryFields, mutationFields) => {
    const map = {};
    (queryFields || []).forEach(f => {
        const named = getNamedType(f.type);
        map[f.name] = {
            name: f.name, path: f.name, description: f.description,
            isQuery: true, isMutation: false,
            typeName: named?.name || null, typeKind: named?.kind || null
        };
    });
    (mutationFields || []).forEach(f => {
        const named = getNamedType(f.type);
        if (map[f.name]) {
            map[f.name].isMutation = true;
        } else {
            map[f.name] = {
                name: f.name, path: f.name, description: f.description,
                isQuery: false, isMutation: true,
                typeName: named?.name || null, typeKind: named?.kind || null
            };
        }
    });
    return Object.values(map).sort((a, b) => a.name.localeCompare(b.name));
};

// True if path itself or any ancestor prefix is in the set
const isCoveredBySet = (path, set) => {
    if (set.has(path)) return true;
    const parts = path.split('.');
    for (let i = 1; i < parts.length; i++) {
        if (set.has(parts.slice(0, i).join('.'))) return true;
    }
    return false;
};

export const McpConfigAdmin = () => {
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
    const {loading: loadingSettings} = useQuery(GET_SETTINGS, {
        fetchPolicy: 'network-only',
        onCompleted: data => {
            setWhitelist(new Set(data?.mcpSettings?.whitelist || []));
            setDirty(false);
        }
    });

    const [saveSettings, {loading: saving}] = useMutation(SAVE_SETTINGS);

    const operations = buildOperationMap(
        queryFieldsData?.queryFields?.fields,
        mutationFieldsData?.mutationFields?.fields
    );

    const expandNode = async (node, expandedPaths, setExpandedPaths) => {
        if (expandedPaths.has(node.path)) {
            setExpandedPaths(prev => { const next = new Set(prev); next.delete(node.path); return next; });
            return;
        }
        setExpandedPaths(prev => new Set([...prev, node.path]));
        if (!node.typeName || typeFields[node.typeName]) return;
        setTypeFields(prev => ({...prev, [node.typeName]: 'loading'}));
        try {
            const result = await apolloClient.query({
                query: GET_TYPE_FIELDS,
                variables: {typeName: node.typeName},
                fetchPolicy: 'cache-first'
            });
            const fields = result.data?.typeFields?.fields || [];
            setTypeFields(prev => ({
                ...prev,
                [node.typeName]: fields.map(f => {
                    const named = getNamedType(f.type);
                    return {name: f.name, description: f.description, typeName: named?.name || null, typeKind: named?.kind || null};
                })
            }));
        } catch (err) {
            console.error('Failed to load fields for type', node.typeName, err);
            setTypeFields(prev => ({...prev, [node.typeName]: []}));
        }
    };

    const toggle = (path) => {
        setWhitelist(prev => {
            const next = new Set(prev);
            if (next.has(path)) {
                next.delete(path);
            } else {
                next.add(path);
                // Remove now-redundant descendants
                next.forEach(e => { if (e !== path && e.startsWith(path + '.')) next.delete(e); });
            }
            return next;
        });
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
            const result = await saveSettings({variables: {whitelist: [...whitelist], blacklist: []}});
            if (result.data?.mcpSaveSettings) {
                setSaveStatus('success');
            } else {
                setSaveStatus('error');
            }
            setDirty(false);
        } catch (err) {
            console.error('Failed to save MCP settings:', err);
            setSaveStatus('error');
        }
    };

    useEffect(() => {
        if (saveStatus) {
            const timer = setTimeout(() => setSaveStatus(null), 4000);
            return () => clearTimeout(timer);
        }
    }, [saveStatus]);

    if (loadingQueryFields || loadingMutationFields || loadingSettings) {
        return (
            <div className={styles.mcp_loading}>
                <Loader size="big"/>
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
                    operations={operations}
                    selected={whitelist}
                    typeFields={typeFields}
                    expandedPaths={expandedWl}
                    onToggle={path => toggle(path)}
                    onSelectAll={selectAll}
                    onUnselectAll={unselectAll}
                    onExpand={node => expandNode(node, expandedWl, setExpandedWl)}
                    styles={styles}
                />
            </div>

            <div className={styles.mcp_actions}>
                {saveStatus === 'success' && (
                    <div className={`${styles.mcp_alert} ${styles['mcp_alert--success']}`}>
                        {t('label.saveSuccess')}
                    </div>
                )}
                {saveStatus === 'error' && (
                    <div className={`${styles.mcp_alert} ${styles['mcp_alert--error']}`}>
                        {t('label.saveError')}
                    </div>
                )}
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

const TreeNode = ({node, depth, selected, typeFields, expandedPaths, onToggle, onExpand, styles}) => {
    const isExpandable = (node.typeKind === 'OBJECT' || node.typeKind === 'INTERFACE') && depth < MAX_TREE_DEPTH;
    const isExpanded = expandedPaths.has(node.path);
    const rawChildren = node.typeName ? typeFields[node.typeName] : null;
    const isLoading = rawChildren === 'loading';
    const covered = isCoveredBySet(node.path, selected);
    const directlySelected = selected.has(node.path);

    const children = (isExpanded && Array.isArray(rawChildren))
        ? rawChildren.map(f => ({...f, path: node.path + '.' + f.name, isQuery: node.isQuery, isMutation: node.isMutation}))
        : null;

    return (
        <div>
            <div
                className={`${styles.mcp_treeRow}${covered && !directlySelected ? ' ' + styles['mcp_treeRow--covered'] : ''}`}
                style={{paddingLeft: `${12 + depth * 18}px`}}
            >
                {isExpandable ? (
                    <button className={styles.mcp_expandBtn} type="button" onClick={() => onExpand(node)}>
                        {isExpanded ? '▾' : '▸'}
                    </button>
                ) : (
                    <span className={styles.mcp_expandPlaceholder}/>
                )}
                <input
                    type="checkbox"
                    className={styles.mcp_checkbox}
                    checked={covered}
                    disabled={covered && !directlySelected}
                    onChange={() => onToggle(node.path)}
                    title={node.description || node.path}
                />
                <span className={styles.mcp_operationName}>{node.name}</span>
                {depth === 0 && (
                    <span className={styles.mcp_typeBadges}>
                        {node.isQuery && <span className={`${styles.mcp_badge} ${styles['mcp_badge--query']}`}>Q</span>}
                        {node.isMutation && <span className={`${styles.mcp_badge} ${styles['mcp_badge--mutation']}`}>M</span>}
                    </span>
                )}
            </div>
            {isExpanded && isLoading && (
                <div className={styles.mcp_treeLoading} style={{paddingLeft: `${12 + (depth + 1) * 18}px`}}>…</div>
            )}
            {children && children.map(child => (
                <TreeNode
                    key={child.path}
                    node={child}
                    depth={depth + 1}
                    selected={selected}
                    typeFields={typeFields}
                    expandedPaths={expandedPaths}
                    onToggle={onToggle}
                    onExpand={onExpand}
                    styles={styles}
                />
            ))}
        </div>
    );
};

const OperationPanel = ({title, hint, emptyHint, selectAllLabel, unselectAllLabel, operations, selected, typeFields, expandedPaths, onToggle, onSelectAll, onUnselectAll, onExpand, styles}) => (
    <div className={styles.mcp_panel}>
        <div className={styles.mcp_panelHeader}>
            <div className={styles.mcp_panelHeaderTop}>
                <h3 className={styles.mcp_panelTitle}>{title}</h3>
                <div className={styles.mcp_panelBulkActions}>
                    <button className={styles.mcp_linkBtn} type="button" onClick={onSelectAll}>{selectAllLabel}</button>
                    <button className={styles.mcp_linkBtn} type="button" onClick={onUnselectAll}>{unselectAllLabel}</button>
                </div>
            </div>
            <Typography className={styles.mcp_panelHint}>{selected.size === 0 ? emptyHint : hint}</Typography>
        </div>
        <div className={styles.mcp_operationList}>
            {operations.map(op => (
                <TreeNode
                    key={op.name}
                    node={op}
                    depth={0}
                    selected={selected}
                    typeFields={typeFields}
                    expandedPaths={expandedPaths}
                    onToggle={onToggle}
                    onExpand={onExpand}
                    styles={styles}
                />
            ))}
        </div>
    </div>
);

export default McpConfigAdmin;
