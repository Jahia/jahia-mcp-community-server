import {
    canonicalEntry,
    isReadOnlyProtected,
    isValidEntry,
    lockOf,
    parseEntry
} from '../policyRules';

describe('parseEntry (same grammar as config/WhitelistEntries.java)', () => {
    test('reads the operation type case-insensitively', () => {
        expect(parseEntry('QUERY:jcr.nodeByPath')).toEqual({type: 'query', path: 'jcr.nodeByPath'});
        expect(parseEntry('Mutation:jcr')).toEqual({type: 'mutation', path: 'jcr'});
    });

    test('keeps the path case: GraphQL names are case-sensitive', () => {
        expect(parseEntry('query:JCR')).toEqual({type: 'query', path: 'JCR'});
    });

    test('returns null for other operation types, unprefixed or malformed entries', () => {
        ['subscription:x', 'jcr', 'query:', 'query:a..b', 'query:1a', null, undefined]
            .forEach(e => expect(parseEntry(e)).toBeNull());
    });
});

describe('canonicalEntry', () => {
    test('lower-cases the prefix only, or returns null when invalid', () => {
        expect(canonicalEntry('QUERY:jcr.X')).toBe('query:jcr.X');
        expect(canonicalEntry('foo:bar')).toBeNull();
    });
});

describe('isValidEntry', () => {
    test('accepts an upper-case prefix, as the server does', () => {
        expect(isValidEntry('QUERY:x')).toBe(true);
        expect(isValidEntry('MUTATION:a.b')).toBe(true);
    });
});

describe('lockOf: paths the server never allows', () => {
    test('the module settings and the OSGi configuration mutations', () => {
        expect(lockOf('mutation:communityMcp.saveSettings')).toBe('settings');
        expect(lockOf('mutation:admin.jahia.configuration')).toBe('configuration');
    });

    test('a prefix written in upper case is locked too', () => {
        expect(lockOf('MUTATION:communityMcp')).toBe('settings');
    });

    test('any mutation path with a grantRoles or revokeRoles segment grants privileges', () => {
        expect(lockOf('mutation:jcr.mutateNode.grantRoles')).toBe('privilege');
        expect(lockOf('mutation:jcr.mutateNodes.revokeRoles.x')).toBe('privilege');
        expect(lockOf('mutation:grantRoles')).toBe('privilege');
    });

    test('personal API tokens can never be managed through the allow list', () => {
        expect(lockOf('mutation:admin.personalApiTokens')).toBe('privilege');
        expect(lockOf('mutation:admin.personalApiTokens.createToken')).toBe('privilege');
    });

    test('queries, look-alike names and parents of a locked path are not locked', () => {
        ['query:jcr.nodeByPath.grantRoles',
            'mutation:jcr.mutateNode.grantRolesX',
            'mutation:admin.personalApiTokensX',
            'mutation:admin',
            'mutation:jcr.mutateNode',
            'foo:bar']
            .forEach(e => expect(lockOf(e)).toBeNull());
    });
});

describe('isReadOnlyProtected: the admin query namespace (configuration values, secrets)', () => {
    test('covers query:admin and everything under it, whatever the prefix case', () => {
        expect(isReadOnlyProtected('query:admin')).toBe(true);
        expect(isReadOnlyProtected('query:admin.jahia')).toBe(true);
        expect(isReadOnlyProtected('QUERY:admin')).toBe(true);
    });

    test('does not cover the admin mutation, look-alike names or invalid entries', () => {
        ['mutation:admin', 'query:administrator', 'query:jcr', 'admin:x'].forEach(e => expect(isReadOnlyProtected(e)).toBe(false));
    });
});
