import fs from 'fs';
import path from 'path';
import i18next from 'i18next';
import {withRawInterpolation} from '../i18n';

const NS = 'jahia-mcp-community-server';
const load = lng => JSON.parse(fs.readFileSync(path.join(__dirname, `../../../main/resources/javascript/locales/${lng}.json`), 'utf8'));
const en = load('en');
const fr = load('fr');

// Jahia's shell runs i18next with the v3 JSON format (key / key_plural), like jcontent.
const instance = async lng => {
    const i18n = i18next.createInstance();
    await i18n.init({lng, compatibilityJSON: 'v3', resources: {en: {[NS]: en}, fr: {[NS]: fr}}, defaultNS: NS});
    return withRawInterpolation(i18n.t.bind(i18n));
};

describe('locales', () => {
    test('en and fr define the same keys', () => {
        expect(Object.keys(fr.label).sort()).toEqual(Object.keys(en.label).sort());
    });

    test('the keys used for the new behaviour exist', () => {
        const keys = 'policyDenyAll problemInvalid problemLockedConfiguration lockedConfigurationNode saveBlocked fieldsLoaded_plural';
        keys.split(' ').forEach(key => expect(en.label[key]).toEqual(expect.any(String)));
    });

    test('field counts use plural forms in English and French', async () => {
        const tEn = await instance('en');
        expect(tEn('label.fieldsLoaded', {count: 1, name: 'admin'})).toBe('admin: 1 field loaded.');
        expect(tEn('label.fieldsLoaded', {count: 2, name: 'admin'})).toBe('admin: 2 fields loaded.');
        const tFr = await instance('fr');
        expect(tFr('label.fieldsLoaded', {count: 1, name: 'admin'})).toBe('admin : 1 champ chargé.');
        expect(tFr('label.fieldsLoaded', {count: 3, name: 'admin'})).toBe('admin : 3 champs chargés.');
    });

    test('interpolated values are not HTML-escaped twice (React escapes them)', async () => {
        const t = await instance('en');
        expect(t('label.filterNoMatch', {filter: 'a&b'})).toContain('“a&b”');
    });
});
