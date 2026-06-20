import {
    buildChildNodes,
    buildOperationMap,
    getNamedType,
    isCoveredBySet,
    isExpandableKind,
    mapTypeFields,
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

describe('buildOperationMap', () => {
    test('returns an empty array when both inputs are missing', () => {
        expect(buildOperationMap(null, null)).toEqual([]);
    });

    test('marks query-only fields as isQuery', () => {
        const result = buildOperationMap([{name: 'currentUser', description: 'd', type: {name: 'User', kind: 'OBJECT'}}], null);
        expect(result).toHaveLength(1);
        expect(result[0]).toMatchObject({name: 'currentUser', path: 'currentUser', isQuery: true, isMutation: false, typeName: 'User', typeKind: 'OBJECT'});
    });

    test('merges a field present in both Query and Mutation into one entry', () => {
        const result = buildOperationMap(
            [{name: 'admin', type: {name: 'AdminQuery', kind: 'OBJECT'}}],
            [{name: 'admin', type: {name: 'AdminMutation', kind: 'OBJECT'}}]
        );
        expect(result).toHaveLength(1);
        expect(result[0]).toMatchObject({name: 'admin', isQuery: true, isMutation: true});
        // TypeName comes from the query pass (first writer wins)
        expect(result[0].typeName).toBe('AdminQuery');
    });

    test('sorts entries alphabetically by name', () => {
        const result = buildOperationMap(
            [{name: 'zeta', type: {}}, {name: 'alpha', type: {}}],
            [{name: 'mid', type: {}}]
        );
        expect(result.map(o => o.name)).toEqual(['alpha', 'mid', 'zeta']);
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

describe('isCoveredBySet', () => {
    test('true when the exact path is in the set', () => {
        expect(isCoveredBySet('admin', new Set(['admin']))).toBe(true);
    });

    test('true when an ancestor prefix is in the set', () => {
        expect(isCoveredBySet('admin.jahia.modules', new Set(['admin']))).toBe(true);
        expect(isCoveredBySet('admin.jahia.modules', new Set(['admin.jahia']))).toBe(true);
    });

    test('false when only a sibling is in the set', () => {
        expect(isCoveredBySet('admin.jahia', new Set(['admin.cluster']))).toBe(false);
    });

    test('false when a descendant (not ancestor) is in the set', () => {
        expect(isCoveredBySet('admin', new Set(['admin.jahia']))).toBe(false);
    });

    test('false for an empty set', () => {
        expect(isCoveredBySet('admin', new Set())).toBe(false);
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
        const node = {path: 'admin', isQuery: true, isMutation: false};
        const raw = [{name: 'jahia', typeName: 'X', typeKind: 'OBJECT'}];
        expect(buildChildNodes(node, raw)).toEqual([
            {name: 'jahia', typeName: 'X', typeKind: 'OBJECT', path: 'admin.jahia', isQuery: true, isMutation: false}
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
