import {useMemo} from 'react';
import {useTranslation} from 'react-i18next';

export const NAMESPACE = 'jahia-mcp-community-server';

// React escapes every string it renders, so i18next must not HTML-escape interpolated values as
// well: 'a&b' would otherwise be displayed as 'a&amp;b'. Returns a NEW t function.
export const withRawInterpolation = t => (key, options) =>
    t(key, {...options, interpolation: {...options?.interpolation, escapeValue: false}});

export const useMcpTranslation = () => {
    const {t} = useTranslation(NAMESPACE);
    // Stable while i18next's own t is: effects that depend on t must not re-run every render.
    return useMemo(() => withRawInterpolation(t), [t]);
};
