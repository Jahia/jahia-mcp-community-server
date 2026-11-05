import {
    allowAllOfType,
    clearAllOfType,
    countEntriesOfType,
    displayPath,
    findCoveringAncestor,
    findProblemEntries,
    hasLoadedMatch,
    hasSelectedDescendant,
    isLockedPath,
    isValidEntry,
    lockOf,
    matchesFilter,
    policyOf,
    removeEntry,
    setsEqual
} from '../operationTree';

const OPS = [
    {name: 'jcr', path: 'query:jcr', isQuery: true, isMutation: false, typeName: 'JCRQuery', typeKind: 'OBJECT'},
    {name: 'admin', path: 'query:admin', isQuery: true, isMutation: false, typeName: 'AdminQuery', typeKind: 'OBJECT'},
    {name: 'jcr', path: 'mutation:jcr', isQuery: false, isMutation: true, typeName: 'JCRMutation', typeKind: 'OBJECT'},
    {name: 'communityMcp', path: 'mutation:communityMcp', isQuery: false, isMutation: true, typeName: 'CommunityMcpMutation', typeKind: 'OBJECT'}
];

describe('isLockedPath', () => {
    test('locks the communityMcp mutation and everything under it', () => {
        expect(isLockedPath('mutation:communityMcp')).toBe(true);
        expect(isLockedPath('mutation:communityMcp.saveSettings')).toBe(true);
    });

    test('does not lock the communityMcp query or look-alike names', () => {
        expect(isLockedPath('query:communityMcp')).toBe(false);
        expect(isLockedPath('mutation:communityMcpX')).toBe(false);
    });

    test('locks the Jahia OSGi configuration mutation and everything under it, like the server', () => {
        expect(lockOf('mutation:admin.jahia.configuration')).toBe('configuration');
        expect(lockOf('mutation:admin.jahia.configuration.value')).toBe('configuration');
        expect(lockOf('mutation:communityMcp')).toBe('settings');
        expect(lockOf('query:admin.jahia.configuration')).toBeNull();
        expect(lockOf('mutation:admin.jahia')).toBeNull();
        expect(lockOf('mutation:admin.jahia.configurationX')).toBeNull();
    });
});

describe('isValidEntry', () => {
    test('accepts query: and mutation: entries with GraphQL names, like the server', () => {
        expect(isValidEntry('query:jcr')).toBe(true);
        expect(isValidEntry('mutation:_a.b1.c_d')).toBe(true);
    });

    test('rejects other operation types and malformed paths', () => {
        ['subscription:x', 'foo:bar', 'query:', 'query:bad path', 'query:a..b', 'query:1a', 'jcr']
            .forEach(e => expect(isValidEntry(e)).toBe(false));
    });
});

describe('displayPath', () => {
    test('strips the operation-type prefix', () => {
        expect(displayPath('query:jcr.nodeByPath')).toBe('jcr.nodeByPath');
        expect(displayPath('plain')).toBe('plain');
    });
});

describe('hasSelectedDescendant', () => {
    test('true when a deeper entry exists under the path', () => {
        expect(hasSelectedDescendant('query:jcr', new Set(['query:jcr.nodeByPath']))).toBe(true);
    });

    test('false for the path itself, siblings and look-alike prefixes', () => {
        expect(hasSelectedDescendant('query:jcr', new Set(['query:jcr', 'query:jcrX.a', 'mutation:jcr.a']))).toBe(false);
    });
});

describe('findCoveringAncestor', () => {
    test('returns the nearest allowed ancestor', () => {
        expect(findCoveringAncestor('query:jcr.a.b', new Set(['query:jcr']))).toBe('query:jcr');
    });

    test('returns null when only the path itself is allowed', () => {
        expect(findCoveringAncestor('query:jcr', new Set(['query:jcr']))).toBeNull();
    });
});

describe('countEntriesOfType', () => {
    test('counts query and mutation entries separately', () => {
        const set = new Set(['query:a', 'query:b.c', 'mutation:a']);
        expect(countEntriesOfType(set, true)).toBe(2);
        expect(countEntriesOfType(set, false)).toBe(1);
    });

    test('does not count entries the server ignores', () => {
        expect(countEntriesOfType(new Set(['query:bad path', 'query:ok']), true)).toBe(1);
    });
});

describe('policyOf (mirrors what the server enforces)', () => {
    test('an empty list is the read-only default', () => {
        expect(policyOf(new Set())).toEqual({kind: 'info', key: 'label.policyReadOnly'});
    });

    test('a non-empty list with no valid entry refuses every operation', () => {
        expect(policyOf(new Set(['subscription:x', 'foo:bar']))).toEqual({kind: 'warning', key: 'label.policyDenyAll'});
    });

    test('valid mutations without a query refuse every query', () => {
        expect(policyOf(new Set(['mutation:a', 'subscription:x']))).toEqual({kind: 'warning', key: 'label.policyNoQuery'});
    });

    test('a list of locked entries only refuses every operation (they are never allowed)', () => {
        expect(policyOf(new Set(['mutation:communityMcp']))).toEqual({kind: 'warning', key: 'label.policyDenyAll'});
        expect(policyOf(new Set(['mutation:admin.jahia.configuration.x', 'foo:bar']))).toEqual({kind: 'warning', key: 'label.policyDenyAll'});
    });

    test('locked entries do not count as allowed mutations', () => {
        expect(policyOf(new Set(['mutation:communityMcp', 'mutation:a']))).toEqual({kind: 'warning', key: 'label.policyNoQuery'});
    });

    test('with a valid query only listed operations pass', () => {
        expect(policyOf(new Set(['query:a', 'foo:bar']))).toEqual({kind: 'info', key: 'label.policyRestricted'});
    });
});

describe('allowAllOfType', () => {
    test('adds every root of the type, drops redundant descendants, skips locked roots', () => {
        const next = allowAllOfType(new Set(['mutation:jcr.addNode', 'query:admin']), OPS, false);
        expect([...next].sort()).toEqual(['mutation:jcr', 'query:admin']);
    });

    test('"Allow all queries" never adds the protected admin namespace (UX-1)', () => {
        const next = allowAllOfType(new Set(), OPS, true);
        expect([...next]).toEqual(['query:jcr']);
    });

    test('"Allow all queries" keeps admin entries the administrator added deliberately', () => {
        const next = allowAllOfType(new Set(['query:admin.jahia', 'query:jcr.nodeByPath']), OPS, true);
        expect([...next].sort()).toEqual(['query:admin.jahia', 'query:jcr']);
    });

    test('"Allow all mutations" skips roots that grant privileges', () => {
        const ops = [...OPS, {name: 'grantRoles', path: 'mutation:grantRoles', isQuery: false, isMutation: true}];
        expect([...allowAllOfType(new Set(), ops, false)]).toEqual(['mutation:jcr']);
    });

    test('does not mutate its input', () => {
        const input = new Set(['query:admin']);
        allowAllOfType(input, OPS, true);
        expect([...input]).toEqual(['query:admin']);
    });
});

describe('clearAllOfType', () => {
    test('removes only the entries of the given type', () => {
        const next = clearAllOfType(new Set(['query:a', 'mutation:b', 'query:c.d', 'foo:bar']), true);
        expect([...next]).toEqual(['mutation:b', 'foo:bar']);
    });
});

describe('removeEntry', () => {
    test('returns a new set without the entry', () => {
        const input = new Set(['query:a', 'query:b']);
        expect([...removeEntry(input, 'query:a')]).toEqual(['query:b']);
        expect(input.size).toBe(2);
    });
});

describe('findProblemEntries', () => {
    test('flags entries whose root operation is not in the schema', () => {
        const problems = findProblemEntries(new Set(['query:jcr.nodeByPath', 'query:gone', 'mutation:old.x']), OPS);
        expect(problems).toEqual([
            {entry: 'mutation:old.x', reason: 'unknown'},
            {entry: 'query:gone', reason: 'unknown'}
        ]);
    });

    test('flags entries the server always refuses', () => {
        expect(findProblemEntries(new Set(['mutation:communityMcp.saveSettings']), OPS))
            .toEqual([{entry: 'mutation:communityMcp.saveSettings', reason: 'locked'}]);
    });

    test('flags the locked configuration mutation with its own reason', () => {
        expect(findProblemEntries(new Set(['mutation:admin.jahia.configuration']), OPS))
            .toEqual([{entry: 'mutation:admin.jahia.configuration', reason: 'lockedConfiguration'}]);
    });

    test('flags privilege-granting mutations with their own reason', () => {
        expect(findProblemEntries(new Set(['mutation:jcr.mutateNode.grantRoles', 'mutation:admin.personalApiTokens']), OPS))
            .toEqual([
                {entry: 'mutation:admin.personalApiTokens', reason: 'lockedPrivilege'},
                {entry: 'mutation:jcr.mutateNode.grantRoles', reason: 'lockedPrivilege'}
            ]);
    });

    test('flags entries the server ignores, even while the schema is unknown', () => {
        expect(findProblemEntries(new Set(['subscription:x', 'query:jcr', 'foo:bar']), OPS)).toEqual([
            {entry: 'foo:bar', reason: 'invalid'},
            {entry: 'subscription:x', reason: 'invalid'}
        ]);
        expect(findProblemEntries(new Set(['subscription:x', 'query:x']), [])).toEqual([
            {entry: 'subscription:x', reason: 'invalid'}
        ]);
    });

    test('reports no unknown root while the schema is unknown', () => {
        expect(findProblemEntries(new Set(['query:x']), [])).toEqual([]);
    });
});

describe('matchesFilter', () => {
    test('matches the path without prefix, case-insensitively', () => {
        expect(matchesFilter('query:jcr.nodeByPath', 'NODEBY')).toBe(true);
        expect(matchesFilter('query:jcr.nodeByPath', 'query')).toBe(false);
    });

    test('an empty filter matches everything', () => {
        expect(matchesFilter('query:x', '')).toBe(true);
    });
});

describe('hasLoadedMatch', () => {
    const root = OPS[0];
    const typeFields = {
        JCRQuery: [{name: 'nodeByPath', typeName: 'JCRNode', typeKind: 'OBJECT'}],
        JCRNode: [{name: 'uuid', typeName: 'String', typeKind: 'SCALAR'}]
    };

    test('finds a match among loaded descendants at any depth', () => {
        expect(hasLoadedMatch(root, typeFields, 'uuid', 0)).toBe(true);
    });

    test('false when nothing loaded matches', () => {
        expect(hasLoadedMatch(root, typeFields, 'missing', 0)).toBe(false);
        expect(hasLoadedMatch(root, {}, 'uuid', 0)).toBe(false);
    });

    test('stops at a recursive type thanks to the depth limit', () => {
        const recursive = {JCRQuery: [{name: 'self', typeName: 'JCRQuery', typeKind: 'OBJECT'}]};
        expect(hasLoadedMatch(root, recursive, 'nope', 0)).toBe(false);
    });
});

describe('setsEqual', () => {
    test('compares content, not identity', () => {
        expect(setsEqual(new Set(['a', 'b']), new Set(['b', 'a']))).toBe(true);
        expect(setsEqual(new Set(['a']), new Set(['a', 'b']))).toBe(false);
        expect(setsEqual(new Set(['a', 'c']), new Set(['a', 'b']))).toBe(false);
    });
});
