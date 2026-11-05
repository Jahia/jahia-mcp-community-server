// Pure helpers for the MCP operation tree / whitelist logic.
// Extracted from CommunityMcpConfig.jsx so they can be unit-tested in isolation.

const MAX_TREE_DEPTH = 5;

// Unwrap NON_NULL / LIST wrappers to reach the named type
const getNamedType = typeObj => {
    if (!typeObj) {
        return null;
    }

    if (typeObj.name) {
        return typeObj;
    }

    return getNamedType(typeObj.ofType);
};

const QUERY_PREFIX = 'query:';
const MUTATION_PREFIX = 'mutation:';

// Build the root operation entries. A field present in both Query and Mutation becomes TWO
// entries: whitelist paths carry the operation type ('query:admin' vs 'mutation:admin'), so
// allowing a query never allows the same-named mutation. Queries first, then mutations, each
// sorted by name.
const buildOperationMap = (queryFields, mutationFields) => {
    const toEntries = (fields, prefix, isQuery) => (fields || [])
        .map(f => {
            const named = getNamedType(f.type);
            return {
                name: f.name, path: prefix + f.name, description: f.description,
                isQuery, isMutation: !isQuery,
                typeName: named?.name || null, typeKind: named?.kind || null
            };
        })
        .sort((a, b) => a.name.localeCompare(b.name));
    return [
        ...toEntries(queryFields, QUERY_PREFIX, true),
        ...toEntries(mutationFields, MUTATION_PREFIX, false)
    ];
};

const OPERATION_PREFIX = /^(query|mutation):/i;

// Mirror the server's reading of stored entries (config/WhitelistEntries.java):
// - prefixes are case-insensitive ('MUTATION:x' is a mutation entry) → lower-cased;
// - an unprefixed (legacy) entry is query-only → 'query:' added;
// - any other 'type:' entry grants nothing on the server → dropped.
// Returns a NEW Set plus whether anything was rewritten (the caller then offers to save it).
const normalizeWhitelist = entries => {
    const normalized = new Set();
    let migrated = false;
    (entries || []).forEach(e => {
        const match = e.match(OPERATION_PREFIX);
        let next = null;
        if (match) {
            next = match[1].toLowerCase() + ':' + e.slice(match[0].length);
        } else if (!e.includes(':')) {
            next = QUERY_PREFIX + e;
        }

        if (next !== e) {
            migrated = true;
        }

        if (next !== null) {
            normalized.add(next);
        }
    });
    return {entries: normalized, migrated};
};

// True if path itself or any ancestor prefix is in the set
const isCoveredBySet = (path, set) => {
    if (set.has(path)) {
        return true;
    }

    const parts = path.split('.');
    for (let i = 1; i < parts.length; i++) {
        if (set.has(parts.slice(0, i).join('.'))) {
            return true;
        }
    }

    return false;
};

// Add `path` to the whitelist set, removing any now-redundant descendants, or
// remove it if already present. Returns a NEW Set (never mutates the input).
const toggleWhitelistPath = (set, path) => {
    const next = new Set(set);
    if (next.has(path)) {
        next.delete(path);
    } else {
        next.add(path);
        next.forEach(e => {
            if (e !== path && e.startsWith(path + '.')) {
                next.delete(e);
            }
        });
    }

    return next;
};

// The server always refuses 'mutation:communityMcp' and everything under it: the allow list
// can never open write access to its own settings. The UI shows it, but it cannot be checked.
const LOCKED_PATH = MUTATION_PREFIX + 'communityMcp';
const isLockedPath = path => path === LOCKED_PATH || path.startsWith(LOCKED_PATH + '.');

// 'query:jcr.nodeByPath' -> 'jcr.nodeByPath' (the form shown to people).
const displayPath = path => {
    const match = path.match(OPERATION_PREFIX);
    return match ? path.slice(match[0].length) : path;
};

// True if a strictly deeper entry exists under `path` (drives the "mixed" checkbox state).
const hasSelectedDescendant = (path, set) => [...set].some(e => e.startsWith(path + '.'));

// The nearest ancestor entry that already allows `path`, or null.
const findCoveringAncestor = (path, set) => {
    const parts = path.split('.');
    for (let i = parts.length - 1; i >= 1; i--) {
        const prefix = parts.slice(0, i).join('.');
        if (set.has(prefix)) {
            return prefix;
        }
    }

    return null;
};

const typePrefix = isQuery => (isQuery ? QUERY_PREFIX : MUTATION_PREFIX);

const countEntriesOfType = (set, isQuery) =>
    [...set].filter(e => e.startsWith(typePrefix(isQuery))).length;

// Allow every root operation of one type (never a locked one). Returns a NEW Set.
const allowAllOfType = (set, operations, isQuery) => {
    const roots = operations
        .filter(op => op.isQuery === isQuery && !isLockedPath(op.path))
        .map(op => op.path);
    const kept = [...set].filter(e => !roots.some(root => e.startsWith(root + '.')));
    return new Set([...kept, ...roots]);
};

const clearAllOfType = (set, isQuery) =>
    new Set([...set].filter(e => !e.startsWith(typePrefix(isQuery))));

const removeEntry = (set, entry) => new Set([...set].filter(e => e !== entry));

// Entries that the tree cannot show or that the server refuses: their root operation is
// missing from the current schema, or they are locked. Sorted for a stable display.
const findProblemEntries = (set, operations) => {
    if (!operations || operations.length === 0) {
        return [];
    }

    const roots = new Set(operations.map(op => op.path));
    return [...set]
        .sort()
        .map(entry => {
            if (isLockedPath(entry)) {
                return {entry, reason: 'locked'};
            }

            return roots.has(entry.split('.')[0]) ? null : {entry, reason: 'unknown'};
        })
        .filter(Boolean);
};

const matchesFilter = (path, filter) =>
    !filter || displayPath(path).toLowerCase().includes(filter.toLowerCase());

const setsEqual = (a, b) => a.size === b.size && [...a].every(e => b.has(e));

// Map the raw fields of an expanded named type into child node descriptors.
const mapTypeFields = fields => (fields || []).map(f => {
    const named = getNamedType(f.type);
    return {
        name: f.name,
        description: f.description,
        typeName: named?.name || null,
        typeKind: named?.kind || null
    };
});

// Build the concrete child nodes shown under a tree node, inheriting the
// parent's query/mutation flags and composing the dot-path.
const buildChildNodes = (node, rawChildren) => {
    if (!Array.isArray(rawChildren)) {
        return null;
    }

    return rawChildren.map(f => ({
        ...f,
        path: node.path + '.' + f.name,
        isQuery: node.isQuery,
        isMutation: node.isMutation
    }));
};

const isExpandableKind = (typeKind, depth) =>
    (typeKind === 'OBJECT' || typeKind === 'INTERFACE') && depth < MAX_TREE_DEPTH;

// True if a descendant whose fields are already loaded matches the filter. Bounded by
// MAX_TREE_DEPTH, so a recursive type (JCRNode.parent: JCRNode) cannot loop.
const hasLoadedMatch = (node, typeFields, filter, depth) => {
    if (!isExpandableKind(node.typeKind, depth)) {
        return false;
    }

    const children = buildChildNodes(node, typeFields[node.typeName]) || [];
    return children.some(child =>
        matchesFilter(child.path, filter) || hasLoadedMatch(child, typeFields, filter, depth + 1));
};

export {
    MAX_TREE_DEPTH,
    getNamedType,
    buildOperationMap,
    normalizeWhitelist,
    isCoveredBySet,
    toggleWhitelistPath,
    mapTypeFields,
    buildChildNodes,
    isExpandableKind,
    isLockedPath,
    displayPath,
    hasSelectedDescendant,
    findCoveringAncestor,
    countEntriesOfType,
    allowAllOfType,
    clearAllOfType,
    removeEntry,
    findProblemEntries,
    matchesFilter,
    hasLoadedMatch,
    setsEqual
};
