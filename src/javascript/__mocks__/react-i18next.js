// react-i18next mock: `t` echoes the key and interpolates {{name}}-style vars.
export const useTranslation = () => ({
    t: (key, vars) => {
        if (vars && typeof vars === 'object') {
            return Object.keys(vars).reduce(
                (acc, k) => acc.replace(new RegExp(`{{\\s*${k}\\s*}}`, 'g'), String(vars[k])),
                key
            );
        }

        return key;
    },
    i18n: {language: 'en', changeLanguage: () => Promise.resolve()}
});

export const initReactI18next = {type: '3rdParty', init: () => {}};
