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

// Build a sorted, de-duplicated map of root operations from the Query and
// Mutation field lists. An operation that exists as both a query and a mutation
// field is merged into a single entry with both flags set.
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

export {
    MAX_TREE_DEPTH,
    getNamedType,
    buildOperationMap,
    isCoveredBySet,
    toggleWhitelistPath,
    mapTypeFields,
    buildChildNodes,
    isExpandableKind
};
