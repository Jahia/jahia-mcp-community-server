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
// Same syntax as the server (config/WhitelistEntries.java): a lower-cased operation type and a
// dot-separated path of GraphQL names.
const VALID_ENTRY = /^(query|mutation):[_A-Za-z]\w*(\.[_A-Za-z]\w*)*$/;

const isValidEntry = entry => VALID_ENTRY.test(entry);

// Mirror the server's reading of stored entries (config/WhitelistEntries.java):
// - prefixes are case-insensitive ('MUTATION:x' is a mutation entry) → lower-cased;
// - an unprefixed (legacy) entry is query-only → 'query:' added.
// Anything else (another operation type such as 'subscription:', a malformed path) is kept AS
// WRITTEN: the server ignores it, but its presence still makes the list non-empty, so dropping it
// here would show — and, on Save, apply — a wider policy than the one in force. The caller lists
// such entries so the administrator removes them explicitly.
// Returns a NEW Set plus whether a valid entry was rewritten (the caller then offers to save it).
const normalizeWhitelist = entries => {
    const normalized = new Set();
    let migrated = false;
    (entries || []).map(e => e.trim()).filter(Boolean).forEach(e => {
        const match = e.match(OPERATION_PREFIX);
        let candidate = e;
        if (match) {
            candidate = match[1].toLowerCase() + ':' + e.slice(match[0].length);
        } else if (!e.includes(':')) {
            candidate = QUERY_PREFIX + e;
        }

        const next = isValidEntry(candidate) ? candidate : e;
        migrated = migrated || next !== e;
        normalized.add(next);
    });
    return {entries: normalized, migrated};
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

// The server always refuses these mutations and everything under them: the allow list can never
// open write access to its own settings ('settings') nor to Jahia's OSGi configuration
// ('configuration'). The UI shows them, but they cannot be checked.
const LOCKED_PATHS = [
    {path: MUTATION_PREFIX + 'communityMcp', lock: 'settings'},
    {path: MUTATION_PREFIX + 'admin.jahia.configuration', lock: 'configuration'}
];
const lockOf = path => {
    const found = LOCKED_PATHS.find(l => path === l.path || path.startsWith(l.path + '.'));
    return found ? found.lock : null;
};

const isLockedPath = path => lockOf(path) !== null;

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
    [...set].filter(e => isValidEntry(e) && e.startsWith(typePrefix(isQuery))).length;

// The policy the server applies to this list (GraphQLWhitelistGate): an empty list is the
// read-only default; a non-empty list whose entries are all ignored (malformed) or never
// allowed (locked) refuses everything.
const policyOf = set => {
    if (set.size === 0) {
        return {kind: 'info', key: 'label.policyReadOnly'};
    }

    const effective = new Set([...set].filter(e => isValidEntry(e) && !isLockedPath(e)));
    if (effective.size === 0) {
        return {kind: 'warning', key: 'label.policyDenyAll'};
    }

    return countEntriesOfType(effective, true) === 0 ?
        {kind: 'warning', key: 'label.policyNoQuery'} :
        {kind: 'info', key: 'label.policyRestricted'};
};

// Entries the server refuses on save: malformed or locked. They must be removed first.
const findBlockingEntries = set => [...set].filter(e => !isValidEntry(e) || isLockedPath(e)).sort();

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

// Entries that the tree cannot show or that the server refuses or ignores: malformed or of
// another operation type ('invalid', reported even before the schema is known), locked, or whose
// root operation is missing from the current schema ('unknown'). Sorted for a stable display.
const problemOf = (entry, roots) => {
    if (!isValidEntry(entry)) {
        return 'invalid';
    }

    const lock = lockOf(entry);
    if (lock) {
        return lock === 'configuration' ? 'lockedConfiguration' : 'locked';
    }

    return roots && !roots.has(entry.split('.')[0]) ? 'unknown' : null;
};

const findProblemEntries = (set, operations) => {
    const roots = operations && operations.length > 0 ? new Set(operations.map(op => op.path)) : null;
    return [...set]
        .sort()
        .map(entry => {
            const reason = problemOf(entry, roots);
            return reason && {entry, reason};
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
    isValidEntry,
    toggleWhitelistPath,
    mapTypeFields,
    buildChildNodes,
    isExpandableKind,
    isLockedPath,
    lockOf,
    policyOf,
    findBlockingEntries,
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
