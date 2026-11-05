import {
    buildChildNodes,
    buildOperationMap,
    getNamedType,
    isExpandableKind,
    mapTypeFields,
    normalizeWhitelist,
    toggleWhitelistPath
} from '../operationTree';

describe('getNamedType', () => {
    test('returns null for a null type', () => {
        expect(getNamedType(null)).toBeNull();
    });

    test('returns the type itself when it already has a name', () => {
        const t = {name: 'String', kind: 'SCALAR'};
        expect(getNamedType(t)).toBe(t);
    });

    test('unwraps NON_NULL and LIST wrappers to the named type', () => {
        const wrapped = {
            name: null, kind: 'NON_NULL',
            ofType: {name: null, kind: 'LIST', ofType: {name: 'JCRNode', kind: 'OBJECT'}}
        };
        expect(getNamedType(wrapped)).toEqual({name: 'JCRNode', kind: 'OBJECT'});
    });
});

describe('getNamedType: [X!]! fields (UX-6)', () => {
    test('unwraps NON_NULL -> LIST -> NON_NULL -> OBJECT', () => {
        const wrapped = {name: null, kind: 'NON_NULL', ofType: {name: null, kind: 'LIST',
            ofType: {name: null, kind: 'NON_NULL', ofType: {name: 'JCRNode', kind: 'OBJECT'}}}};
        expect(getNamedType(wrapped)).toEqual({name: 'JCRNode', kind: 'OBJECT'});
    });
});

describe('buildOperationMap', () => {
    test('returns an empty array when both inputs are missing', () => {
        expect(buildOperationMap(null, null)).toEqual([]);
    });

    test('qualifies query fields with the query: prefix', () => {
        const result = buildOperationMap([{name: 'currentUser', description: 'd', type: {name: 'User', kind: 'OBJECT'}}], null);
        expect(result).toHaveLength(1);
        expect(result[0]).toMatchObject({name: 'currentUser', path: 'query:currentUser', isQuery: true, isMutation: false, typeName: 'User', typeKind: 'OBJECT'});
    });

    test('keeps a field present in both Query and Mutation as two distinct entries', () => {
        const result = buildOperationMap(
            [{name: 'admin', type: {name: 'AdminQuery', kind: 'OBJECT'}}],
            [{name: 'admin', type: {name: 'AdminMutation', kind: 'OBJECT'}}]
        );
        expect(result).toHaveLength(2);
        expect(result[0]).toMatchObject({path: 'query:admin', isQuery: true, isMutation: false, typeName: 'AdminQuery'});
        expect(result[1]).toMatchObject({path: 'mutation:admin', isQuery: false, isMutation: true, typeName: 'AdminMutation'});
    });

    test('lists queries first, then mutations, each sorted alphabetically', () => {
        const result = buildOperationMap(
            [{name: 'zeta', type: {}}, {name: 'alpha', type: {}}],
            [{name: 'mid', type: {}}, {name: 'beta', type: {}}]
        );
        expect(result.map(o => o.path)).toEqual(['query:alpha', 'query:zeta', 'mutation:beta', 'mutation:mid']);
    });

    test('resolves the named type through wrappers', () => {
        const result = buildOperationMap(
            [{name: 'nodes', type: {kind: 'LIST', ofType: {name: 'JCRNode', kind: 'OBJECT'}}}],
            null
        );
        expect(result[0].typeName).toBe('JCRNode');
        expect(result[0].typeKind).toBe('OBJECT');
    });
});

describe('normalizeWhitelist', () => {
    test('keeps prefixed entries as they are', () => {
        const {entries, migrated} = normalizeWhitelist(['query:jcr', 'mutation:jcr.deleteNode']);
        expect([...entries]).toEqual(['query:jcr', 'mutation:jcr.deleteNode']);
        expect(migrated).toBe(false);
    });

    test('rewrites a legacy unprefixed entry as query-only, like the server does', () => {
        const {entries, migrated} = normalizeWhitelist(['jcr', 'mutation:x']);
        expect([...entries]).toEqual(['query:jcr', 'mutation:x']);
        expect(migrated).toBe(true);
    });

    test('reads the prefix case-insensitively, like the server, and lower-cases it', () => {
        const {entries, migrated} = normalizeWhitelist(['MUTATION:jcr.deleteNode', 'Query:jcr']);
        expect([...entries]).toEqual(['mutation:jcr.deleteNode', 'query:jcr']);
        expect(migrated).toBe(true);
    });

    test('keeps entries the server ignores (unknown type, malformed) as they are, never drops them', () => {
        const {entries, migrated} = normalizeWhitelist(['subscription:jcr', 'query:x', 'foo:bar', 'query:bad path']);
        expect([...entries]).toEqual(['subscription:jcr', 'query:x', 'foo:bar', 'query:bad path']);
        expect(migrated).toBe(false);
    });

    test('a malformed legacy entry is kept as written, not turned into a query entry', () => {
        const {entries, migrated} = normalizeWhitelist(['bad path', ' query:x ', '']);
        expect([...entries]).toEqual(['bad path', 'query:x']);
        expect(migrated).toBe(false);
    });

    test('handles a missing list', () => {
        expect([...normalizeWhitelist(null).entries]).toEqual([]);
    });
});

describe('toggleWhitelistPath', () => {
    test('adds a path that is absent', () => {
        const result = toggleWhitelistPath(new Set(), 'admin');
        expect([...result]).toEqual(['admin']);
    });

    test('removes a path that is present', () => {
        const result = toggleWhitelistPath(new Set(['admin']), 'admin');
        expect([...result]).toEqual([]);
    });

    test('removes now-redundant descendants when adding an ancestor', () => {
        const result = toggleWhitelistPath(new Set(['admin.jahia', 'admin.cluster', 'jcr']), 'admin');
        expect([...result].sort()).toEqual(['admin', 'jcr']);
    });

    test('does not mutate the input set', () => {
        const input = new Set(['admin']);
        toggleWhitelistPath(input, 'jcr');
        expect([...input]).toEqual(['admin']);
    });
});

describe('mapTypeFields', () => {
    test('returns [] for missing input', () => {
        expect(mapTypeFields(undefined)).toEqual([]);
    });

    test('maps fields and resolves named types through wrappers', () => {
        const fields = [
            {name: 'name', description: 'the name', type: {name: 'String', kind: 'SCALAR'}},
            {name: 'child', type: {kind: 'NON_NULL', ofType: {name: 'JCRNode', kind: 'OBJECT'}}}
        ];
        expect(mapTypeFields(fields)).toEqual([
            {name: 'name', description: 'the name', typeName: 'String', typeKind: 'SCALAR'},
            {name: 'child', description: undefined, typeName: 'JCRNode', typeKind: 'OBJECT'}
        ]);
    });
});

describe('buildChildNodes', () => {
    test('returns null when rawChildren is not an array', () => {
        expect(buildChildNodes({path: 'admin', isQuery: true, isMutation: false}, 'loading')).toBeNull();
        expect(buildChildNodes({path: 'admin'}, null)).toBeNull();
    });

    test('composes dot-paths and inherits parent query/mutation flags', () => {
        const node = {path: 'query:admin', isQuery: true, isMutation: false};
        const raw = [{name: 'jahia', typeName: 'X', typeKind: 'OBJECT'}];
        expect(buildChildNodes(node, raw)).toEqual([
            {name: 'jahia', typeName: 'X', typeKind: 'OBJECT', path: 'query:admin.jahia', isQuery: true, isMutation: false}
        ]);
    });
});

describe('isExpandableKind', () => {
    test('OBJECT and INTERFACE are expandable below max depth', () => {
        expect(isExpandableKind('OBJECT', 0)).toBe(true);
        expect(isExpandableKind('INTERFACE', 3)).toBe(true);
    });

    test('scalars and enums are never expandable', () => {
        expect(isExpandableKind('SCALAR', 0)).toBe(false);
        expect(isExpandableKind('ENUM', 0)).toBe(false);
    });

    test('stops expanding at max depth', () => {
        expect(isExpandableKind('OBJECT', 5)).toBe(false);
        expect(isExpandableKind('OBJECT', 6)).toBe(false);
    });
});
