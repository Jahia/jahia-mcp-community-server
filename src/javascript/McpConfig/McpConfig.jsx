import React, {useEffect, useState} from 'react';
import {useMutation, useQuery} from '@apollo/client';
import {useTranslation} from 'react-i18next';
import {Button, Loader, Typography} from '@jahia/moonstone';
import styles from './McpConfig.scss';
import {GET_MUTATION_FIELDS, GET_QUERY_FIELDS, GET_SETTINGS, SAVE_SETTINGS} from './McpConfig.gql';

const buildOperationMap = (queryFields, mutationFields) => {
    const map = {};
    (queryFields || []).forEach(f => {
        map[f.name] = {name: f.name, description: f.description, isQuery: true, isMutation: false};
    });
    (mutationFields || []).forEach(f => {
        if (map[f.name]) {
            map[f.name].isMutation = true;
        } else {
            map[f.name] = {name: f.name, description: f.description, isQuery: false, isMutation: true};
        }
    });
    return Object.values(map).sort((a, b) => a.name.localeCompare(b.name));
};

export const McpConfigAdmin = () => {
    const {t} = useTranslation('jahia-mcp-community-server');
    const [saveStatus, setSaveStatus] = useState(null);
    const [whitelist, setWhitelist] = useState(new Set());
    const [blacklist, setBlacklist] = useState(new Set());
    const [customWhitelist, setCustomWhitelist] = useState('');
    const [customBlacklist, setCustomBlacklist] = useState('');
    const [rawSettings, setRawSettings] = useState(null);
    const [dirty, setDirty] = useState(false);

    const {loading: loadingQueryFields, data: queryFieldsData} = useQuery(GET_QUERY_FIELDS, {fetchPolicy: 'cache-first'});
    const {loading: loadingMutationFields, data: mutationFieldsData} = useQuery(GET_MUTATION_FIELDS, {fetchPolicy: 'cache-first'});
    const {loading: loadingSettings} = useQuery(GET_SETTINGS, {
        fetchPolicy: 'network-only',
        onCompleted: data => setRawSettings(data?.mcpSettings)
    });

    const [saveSettings, {loading: saving}] = useMutation(SAVE_SETTINGS);

    const operations = buildOperationMap(
        queryFieldsData?.queryFields?.fields,
        mutationFieldsData?.mutationFields?.fields
    );

    // Once both ops and raw settings are available, split entries into checkboxes + custom text
    useEffect(() => {
        if (!rawSettings || loadingQueryFields || loadingMutationFields) return;
        const opNames = new Set(operations.map(op => op.name));
        const allWl = rawSettings.whitelist || [];
        const allBl = rawSettings.blacklist || [];
        setWhitelist(new Set(allWl.filter(e => opNames.has(e))));
        setCustomWhitelist(allWl.filter(e => !opNames.has(e)).join('\n'));
        setBlacklist(new Set(allBl.filter(e => opNames.has(e))));
        setCustomBlacklist(allBl.filter(e => !opNames.has(e)).join('\n'));
        setDirty(false);
    }, [rawSettings, loadingQueryFields, loadingMutationFields]); // eslint-disable-line

    const parseCustom = str => str.split(/[\n,]+/).map(s => s.trim()).filter(Boolean);

    const selectAll = (listSetter, otherSetter) => {
        const allNames = new Set(operations.map(op => op.name));
        listSetter(allNames);
        otherSetter(prev => {
            const next = new Set(prev);
            allNames.forEach(n => next.delete(n));
            return next;
        });
        setDirty(true);
    };

    const unselectAll = listSetter => {
        listSetter(new Set());
        setDirty(true);
    };

    const toggle = (listSetter, otherSetter, name) => {
        listSetter(prev => {
            const next = new Set(prev);
            if (next.has(name)) {
                next.delete(name);
            } else {
                next.add(name);
                // Adding to one list removes from the other to avoid contradictions
                otherSetter(other => {
                    const o = new Set(other);
                    o.delete(name);
                    return o;
                });
            }
            return next;
        });
        setDirty(true);
    };

    const handleSave = async () => {
        setSaveStatus(null);
        try {
            const result = await saveSettings({
                variables: {
                    whitelist: [...whitelist, ...parseCustom(customWhitelist)],
                    blacklist: [...blacklist, ...parseCustom(customBlacklist)]
                }
            });
            setSaveStatus(result.data?.mcpSaveSettings ? 'success' : 'error');
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
                    customPathsLabel={t('label.customPaths')}
                    customPathsPlaceholder={t('label.customPathsPlaceholder')}
                    operations={operations}
                    selected={whitelist}
                    customPaths={customWhitelist}
                    onToggle={name => toggle(setWhitelist, setBlacklist, name)}
                    onSelectAll={() => selectAll(setWhitelist, setBlacklist)}
                    onUnselectAll={() => unselectAll(setWhitelist)}
                    onCustomPathsChange={v => { setCustomWhitelist(v); setDirty(true); }}
                    styles={styles}
                />
                <OperationPanel
                    title={t('label.blockList')}
                    hint={t('label.blockListHint')}
                    emptyHint={t('label.blockListEmpty')}
                    selectAllLabel={t('label.selectAll')}
                    unselectAllLabel={t('label.unselectAll')}
                    customPathsLabel={t('label.customPaths')}
                    customPathsPlaceholder={t('label.customPathsPlaceholder')}
                    operations={operations}
                    selected={blacklist}
                    customPaths={customBlacklist}
                    onToggle={name => toggle(setBlacklist, setWhitelist, name)}
                    onSelectAll={() => selectAll(setBlacklist, setWhitelist)}
                    onUnselectAll={() => unselectAll(setBlacklist)}
                    onCustomPathsChange={v => { setCustomBlacklist(v); setDirty(true); }}
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

const OperationPanel = ({title, hint, emptyHint, selectAllLabel, unselectAllLabel, customPathsLabel, customPathsPlaceholder, operations, selected, customPaths, onToggle, onSelectAll, onUnselectAll, onCustomPathsChange, styles}) => (
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
                <label key={op.name} className={styles.mcp_operationRow} title={op.description || ''}>
                    <input
                        type="checkbox"
                        className={styles.mcp_checkbox}
                        checked={selected.has(op.name)}
                        onChange={() => onToggle(op.name)}
                    />
                    <span className={styles.mcp_operationName}>{op.name}</span>
                    <span className={styles.mcp_typeBadges}>
                        {op.isQuery && <span className={`${styles.mcp_badge} ${styles['mcp_badge--query']}`}>Q</span>}
                        {op.isMutation && <span className={`${styles.mcp_badge} ${styles['mcp_badge--mutation']}`}>M</span>}
                    </span>
                </label>
            ))}
        </div>
        <div className={styles.mcp_customPaths}>
            <span className={styles.mcp_customPathsLabel}>{customPathsLabel}</span>
            <textarea
                className={styles.mcp_customPathsTextarea}
                value={customPaths}
                onChange={e => onCustomPathsChange(e.target.value)}
                placeholder={customPathsPlaceholder}
                spellCheck={false}
            />
        </div>
    </div>
);

export default McpConfigAdmin;
