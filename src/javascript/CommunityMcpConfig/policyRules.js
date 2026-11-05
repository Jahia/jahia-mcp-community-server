// The server's access rules, in ONE place on the UI side. Mirrors config/WhitelistEntries.java:
// keep both in sync when the entry grammar, the never-allowed paths or the protected query
// namespaces change.

export const QUERY_PREFIX = 'query:';
export const MUTATION_PREFIX = 'mutation:';

const GRAPHQL_NAME = '[_A-Za-z]\\w*';
// The operation type is case-insensitive ('QUERY:x' is a query entry); GraphQL names are not.
const ENTRY = new RegExp(`^(query|mutation):(${GRAPHQL_NAME}(?:\\.${GRAPHQL_NAME})*)$`, 'i');

// {type: 'query'|'mutation', path} for a well-formed prefixed entry, otherwise null.
export const parseEntry = entry => {
    const match = typeof entry === 'string' ? entry.match(ENTRY) : null;
    return match ? {type: match[1].toLowerCase(), path: match[2]} : null;
};

export const isValidEntry = entry => parseEntry(entry) !== null;

// 'QUERY:jcr' -> 'query:jcr'; null when the entry is not valid.
export const canonicalEntry = entry => {
    const parsed = parseEntry(entry);
    return parsed ? `${parsed.type}:${parsed.path}` : null;
};

// Never allowed, whatever the list says: the path itself and everything under it.
// - settings: the module's own settings (communityMcp);
// - configuration: Jahia's OSGi configuration, which could rewrite these settings;
// - privilege: operations that grant privileges (personal API tokens, role grants).
const NEVER_ALLOWED_ROOTS = [
    {path: MUTATION_PREFIX + 'communityMcp', lock: 'settings'},
    {path: MUTATION_PREFIX + 'admin.jahia.configuration', lock: 'configuration'},
    {path: MUTATION_PREFIX + 'admin.personalApiTokens', lock: 'privilege'}
];
// Any mutation path with one of these segments grants or removes roles.
const PRIVILEGE_SEGMENTS = ['grantRoles', 'revokeRoles'];

// Query namespaces the read-only default refuses and that "Allow all queries" never adds: they
// expose configuration values, secrets included.
export const READ_ONLY_PROTECTED_QUERIES = [QUERY_PREFIX + 'admin'];

const isAtOrUnder = (entry, root) => entry === root || entry.startsWith(root + '.');

// 'settings' | 'configuration' | 'privilege' when the server always refuses the entry, else null.
export const lockOf = entry => {
    const parsed = parseEntry(entry);
    if (!parsed || parsed.type !== 'mutation') {
        return null;
    }

    const canonical = `${parsed.type}:${parsed.path}`;
    const root = NEVER_ALLOWED_ROOTS.find(r => isAtOrUnder(canonical, r.path));
    if (root) {
        return root.lock;
    }

    return parsed.path.split('.').some(segment => PRIVILEGE_SEGMENTS.includes(segment)) ? 'privilege' : null;
};

export const isLockedPath = entry => lockOf(entry) !== null;

export const isReadOnlyProtected = entry => {
    const canonical = canonicalEntry(entry);
    return canonical !== null && READ_ONLY_PROTECTED_QUERIES.some(root => isAtOrUnder(canonical, root));
};
