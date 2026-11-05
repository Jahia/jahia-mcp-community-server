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

const FALLBACK_LANGUAGE = 'en';

// The language the panel's strings are actually written in: the first UI language that has a
// bundle for this module, else the English fallback. Declared on the panel root so screen
// readers do not read English text with, say, a German voice (WCAG 3.1.2).
export const resolveLanguage = i18n => {
    const candidates = i18n?.languages || (i18n?.language ? [i18n.language] : []);
    return candidates.find(lng => i18n.hasResourceBundle(lng, NAMESPACE)) || FALLBACK_LANGUAGE;
};

export const useMcpLanguage = () => {
    const {i18n} = useTranslation(NAMESPACE);
    return resolveLanguage(i18n);
};
