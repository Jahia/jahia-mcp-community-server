import React, {useEffect, useRef, useState} from 'react';
import PropTypes from 'prop-types';
import styles from './CommunityMcpConfig.scss';
import {
    buildChildNodes,
    displayPath,
    findCoveringAncestor,
    hasLoadedMatch,
    hasSelectedDescendant,
    isExpandableKind,
    lockOf,
    matchesFilter
} from './operationTree';
import {READ_ONLY_PROTECTED_QUERIES} from './policyRules';

// WAI-ARIA APG "tree view" with checkable items (aria-checked true/false/mixed).
// One tab stop per tree (roving tabindex); Up/Down/Home/End move, Right expands or enters the
// children, Left collapses or goes to the parent, Space/Enter toggle the focused item.
// The visual checkbox and chevron are mouse targets only (aria-hidden): the treeitem carries
// the name, the state and the keyboard behaviour.

const TREEITEM = '[role="treeitem"]';

const treeItemsOf = element => [...element.closest('[role="tree"]').querySelectorAll(TREEITEM)];

const focusRelative = (element, delta) => {
    const items = treeItemsOf(element);
    items[items.indexOf(element) + delta]?.focus();
};

const focusEdge = (element, isLast) => {
    const items = treeItemsOf(element);
    items[isLast ? items.length - 1 : 0]?.focus();
};

const Chevron = ({isOpen}) => (
    <svg width="16"
         height="16"
         viewBox="0 0 16 16"
         focusable="false"
         aria-hidden="true"
         className={isOpen ? styles.mcp_chevronOpen : styles.mcp_chevron}
    >
        <path d="M6 3l5 5-5 5" fill="none" stroke="currentColor" strokeWidth="2"/>
    </svg>
);

const CheckMark = ({state}) => {
    if (state === 'true') {
        return (
            <svg width="16" height="16" viewBox="0 0 16 16" focusable="false" aria-hidden="true">
                <path d="M3 8.5l3.2 3L13 4.5" fill="none" stroke="currentColor" strokeWidth="2.4"/>
            </svg>
        );
    }

    if (state === 'mixed') {
        return (
            <svg width="16" height="16" viewBox="0 0 16 16" focusable="false" aria-hidden="true">
                <path d="M3.5 8h9" fill="none" stroke="currentColor" strokeWidth="2.4"/>
            </svg>
        );
    }

    return null;
};

const LOCK_NOTE_KEYS = {
    settings: 'label.lockedNode',
    configuration: 'label.lockedConfigurationNode',
    privilege: 'label.lockedPrivilegeNode'
};

const checkedState = (path, selected, coveringAncestor) => {
    if (selected.has(path) || coveringAncestor) {
        return 'true';
    }

    return hasSelectedDescendant(path, selected) ? 'mixed' : 'false';
};

// Keyboard map of the APG tree pattern. `item` describes the focused treeitem.
const KEY_HANDLERS = {
    ArrowDown: element => focusRelative(element, 1),
    ArrowUp: element => focusRelative(element, -1),
    Home: element => focusEdge(element, false),
    End: element => focusEdge(element, true),
    ArrowRight: (element, item) => {
        if (item.isExpanded) {
            element.querySelector(`[role="group"] > ${TREEITEM}`)?.focus();
        } else if (item.isExpandable) {
            item.setExpanded(true);
        }
    },
    ArrowLeft: (element, item) => {
        if (item.isExpanded) {
            item.setExpanded(false);
        } else {
            element.parentElement.closest(TREEITEM)?.focus();
        }
    },
    ' ': (element, item) => item.toggle(),
    Enter: (element, item) => item.toggle()
};

// Everything a treeitem shows, derived from the node, the allow list and the filter.
const describeNode = (node, depth, isUnfiltered, {selected, typeFields, expanded, filter, t}) => {
    const lock = lockOf(node.path);
    const isLocked = lock !== null;
    const isExpandable = !isLocked && isExpandableKind(node.typeKind, depth);
    const raw = node.typeName ? typeFields[node.typeName] : undefined;

    const isShowingAll = !filter || isUnfiltered || matchesFilter(node.path, filter);
    const isForcedOpen = !isShowingAll && hasLoadedMatch(node, typeFields, filter, depth);
    const isExpanded = isExpandable && (expanded.has(node.path) || isForcedOpen);
    const isChildVisible = child => isShowingAll || matchesFilter(child.path, filter) ||
        hasLoadedMatch(child, typeFields, filter, depth + 1);
    const children = isExpanded ? (buildChildNodes(node, raw) || []).filter(isChildVisible) : [];

    const coveringAncestor = isLocked ? null : findCoveringAncestor(node.path, selected);
    const id = `mcp-node-${node.path}`;
    const notes = [
        READ_ONLY_PROTECTED_QUERIES.includes(node.path) && {id: `${id}-protected`, className: styles.mcp_note, text: t('label.adminQueryNode')},
        node.description && {id: `${id}-desc`, className: styles.mcp_operationDesc, text: node.description, lang: 'en'},
        coveringAncestor && {id: `${id}-covered`, className: styles.mcp_note, text: t('label.coveredBy', {parent: displayPath(coveringAncestor)})},
        isLocked && {id: `${id}-locked`, className: styles.mcp_note, text: t(LOCK_NOTE_KEYS[lock])}
    ].filter(Boolean);

    return {
        id,
        isExpandable,
        isExpanded,
        isShowingAll,
        isLoading: raw === 'loading',
        hasError: raw === 'error',
        children,
        notes,
        isReadOnly: Boolean(coveringAncestor) || isLocked,
        ariaChecked: isLocked ? 'false' : checkedState(node.path, selected, coveringAncestor)
    };
};

const TreeItem = ({node, depth, posinset, setsize, isUnfiltered, ctx}) => {
    const {activePath, setActivePath, onToggle, onSetExpanded, onAnnounce, t} = ctx;
    const {id, isExpandable, isExpanded, isShowingAll, isLoading, hasError, children, notes, isReadOnly, ariaChecked} =
        describeNode(node, depth, isUnfiltered, ctx);
    const nameId = `${id}-name`;

    const toggle = () => {
        if (isReadOnly) {
            // Say why instead of silently ignoring the key or click.
            onAnnounce(notes[notes.length - 1].text);
            return;
        }

        onToggle(node.path);
    };

    const handleKeyDown = e => {
        const handler = KEY_HANDLERS[e.key];
        if (e.target !== e.currentTarget || !handler) {
            return;
        }

        e.preventDefault();
        handler(e.currentTarget, {isExpandable, isExpanded, toggle, setExpanded: isOpen => onSetExpanded(node, isOpen)});
    };

    const itemRef = useRef(null);
    const handleRowClick = () => {
        itemRef.current.focus();
        toggle();
    };

    const handleToggleClick = e => {
        e.stopPropagation();
        itemRef.current.focus();
        onSetExpanded(node, !isExpanded);
    };

    const rowClass = [
        styles.mcp_treeRow,
        isReadOnly ? styles['mcp_treeRow--readonly'] : ''
    ].filter(Boolean).join(' ');

    return (
        <li ref={itemRef}
            role="treeitem"
            id={id}
            data-path={node.path}
            className={styles.mcp_treeItem}
            tabIndex={activePath === node.path ? 0 : -1}
            aria-level={depth + 1}
            aria-setsize={setsize}
            aria-posinset={posinset}
            aria-checked={ariaChecked}
            aria-expanded={isExpandable ? isExpanded : undefined}
            aria-labelledby={nameId}
            aria-describedby={notes.length > 0 ? notes.map(n => n.id).join(' ') : undefined}
            onKeyDown={handleKeyDown}
            onFocus={e => e.target === e.currentTarget && setActivePath(node.path)}
        >
            {/* Mouse target; the keyboard path is the treeitem itself. */}
            <div className={rowClass} onClick={handleRowClick}>
                {isExpandable ? (
                    <span className={styles.mcp_toggle} data-testid="mcp-toggle" aria-hidden="true" onClick={handleToggleClick}>
                        <Chevron isOpen={isExpanded}/>
                    </span>
                ) : <span className={styles.mcp_toggle} aria-hidden="true"/>}
                <span className={`${styles.mcp_check} ${styles[`mcp_check--${ariaChecked}`]}`} aria-hidden="true">
                    <CheckMark state={ariaChecked}/>
                </span>
                <span className={styles.mcp_nodeText}>
                    <span id={nameId} className={styles.mcp_operationName} lang="en">{node.name}</span>
                    {notes.map(n => <span key={n.id} id={n.id} className={n.className} lang={n.lang}>{n.text}</span>)}
                    {isLoading && <span className={styles.mcp_note}>{t('label.loadingFields')}</span>}
                    {hasError && <span role="alert" className={styles.mcp_noteError}>{t('label.loadFieldsError', {name: node.name})}</span>}
                </span>
            </div>
            {children.length > 0 && (
                <ul role="group" className={styles.mcp_treeGroup}>
                    {children.map((child, i) => (
                        <TreeItem key={child.path}
                                  node={child}
                                  depth={depth + 1}
                                  posinset={i + 1}
                                  setsize={children.length}
                                  isUnfiltered={isShowingAll}
                                  ctx={ctx}/>
                    ))}
                </ul>
            )}
        </li>
    );
};

const nodeShape = PropTypes.shape({
    name: PropTypes.string.isRequired,
    path: PropTypes.string.isRequired,
    description: PropTypes.string,
    typeName: PropTypes.string,
    typeKind: PropTypes.string
});

TreeItem.propTypes = {
    node: nodeShape.isRequired,
    depth: PropTypes.number.isRequired,
    posinset: PropTypes.number.isRequired,
    setsize: PropTypes.number.isRequired,
    isUnfiltered: PropTypes.bool.isRequired,
    ctx: PropTypes.object.isRequired
};

export const OperationTree = ({roots, labelledBy, selected, typeFields, expanded, filter, onToggle, onSetExpanded, onAnnounce, t}) => {
    const treeRef = useRef(null);
    const [activePath, setActivePath] = useState(null);

    // Keep exactly one tab stop: when the active item disappears (collapse, filter), the first
    // visible item takes it.
    useEffect(() => {
        const tree = treeRef.current;
        if (tree && !tree.querySelector(`${TREEITEM}[tabindex="0"]`)) {
            const first = tree.querySelector(TREEITEM);
            if (first) {
                setActivePath(first.dataset.path);
            }
        }
    }, [activePath, roots, expanded, typeFields, filter]);

    const ctx = {selected, typeFields, expanded, filter, activePath, setActivePath, onToggle, onSetExpanded, onAnnounce, t};
    return (
        <ul ref={treeRef} role="tree" aria-labelledby={labelledBy} className={styles.mcp_tree}>
            {roots.map((node, i) => (
                <TreeItem key={node.path}
                          node={node}
                          depth={0}
                          posinset={i + 1}
                          setsize={roots.length}
                          isUnfiltered={false}
                          ctx={ctx}/>
            ))}
        </ul>
    );
};

OperationTree.propTypes = {
    roots: PropTypes.arrayOf(nodeShape).isRequired,
    labelledBy: PropTypes.string.isRequired,
    selected: PropTypes.instanceOf(Set).isRequired,
    typeFields: PropTypes.object.isRequired,
    expanded: PropTypes.instanceOf(Set).isRequired,
    filter: PropTypes.string.isRequired,
    onToggle: PropTypes.func.isRequired,
    onSetExpanded: PropTypes.func.isRequired,
    onAnnounce: PropTypes.func.isRequired,
    t: PropTypes.func.isRequired
};

export default OperationTree;
