// react-i18next mock: `t` echoes the key and interpolates {{name}}-style vars.
// Like the real hook, `t` is a stable reference across renders.
const t = (key, vars) => {
    if (vars && typeof vars === 'object') {
        return Object.keys(vars).reduce(
            (acc, k) => acc.replace(new RegExp(`{{\\s*${k}\\s*}}`, 'g'), String(vars[k])),
            key
        );
    }

    return key;
};

const i18n = {
    language: 'en',
    languages: ['en'],
    hasResourceBundle: () => true,
    changeLanguage: () => Promise.resolve()
};
const translation = {t, i18n};

export const useTranslation = () => translation;

export const initReactI18next = {type: '3rdParty', init: () => {}};
