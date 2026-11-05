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

    test('the serverChanged key exists in both languages', () => {
        expect(en.label.serverChanged).toEqual(expect.any(String));
        expect(fr.label.serverChanged).toEqual(expect.any(String));
    });

    test('every locked-entry reason says the entry blocks Save', () => {
        ['problemLocked', 'problemLockedConfiguration', 'problemLockedPrivilege'].forEach(key => {
            expect(en.label[key]).toContain('Remove it before saving.');
            expect(fr.label[key]).toContain('Supprimez-la avant d\'enregistrer.');
        });
    });

    test('the empty-list texts say the admin query namespace is refused too', () => {
        ['policyReadOnly', 'helpDefault', 'queriesNoteDefault', 'confirmClearBody'].forEach(key => {
            expect(en.label[key]).toContain('except the admin namespace');
            expect(fr.label[key]).toContain('sauf l\'espace de noms admin');
        });
    });

    test('"Allow all queries" help and the admin row say the admin namespace is left out (UX-1)', () => {
        expect(en.label.helpAllowAll).toContain('except the admin namespace');
        expect(fr.label.helpAllowAll).toContain('sauf l\'espace de noms admin');
        ['adminQueryNode', 'confirmAllowAdminBody'].forEach(key => {
            expect(en.label[key]).toMatch(/secrets/);
            expect(fr.label[key]).toMatch(/secrets/);
        });
    });

    test('the help names every never-allowed mutation (ARCH-5)', () => {
        ['communityMcp', 'admin.jahia.configuration', 'admin.personalApiTokens', 'grantRoles', 'revokeRoles'].forEach(name => {
            expect(en.label.helpDefault).toContain(name);
            expect(fr.label.helpDefault).toContain(name);
        });
    });

    test('the read-only texts warn that queries still read everything readable (SEC-1)', () => {
        ['policyReadOnly', 'helpDefault'].forEach(key => {
            expect(en.label[key]).toContain('user data');
            expect(en.label[key]).toContain('In production');
            expect(fr.label[key]).toContain('données des utilisateurs');
            expect(fr.label[key]).toContain('En production');
        });
    });

    test('the whitelist setting is named once, to map the allow list to the API and configuration (UX-17)', () => {
        [en, fr].forEach(locale => {
            const naming = Object.keys(locale.label).filter(key => locale.label[key].includes('whitelist'));
            expect(naming).toEqual(['helpEntries']);
            expect(locale.label.helpEntries).toContain('settings.whitelist');
        });
    });

    test('abbreviations are expanded on first use (A11Y-7)', () => {
        expect(en.label.title).toContain('Model Context Protocol (MCP)');
        expect(fr.label.title).toContain('MCP (Model Context Protocol)');
        expect(en.label.description).toContain('artificial intelligence (AI)');
        expect(fr.label.description).toContain('intelligence artificielle (IA)');
        [en, fr].forEach(locale => {
            expect(locale.label.helpEntries).toContain('OSGi (Open Service Gateway initiative)');
            expect(locale.label.helpDefault).toContain('Java Content Repository');
            expect(locale.label.helpDefault).toMatch(/application programming interface|interface de programmation/);
        });
    });

    test('the button that empties the list has the same name as the confirmation (A11Y-11)', () => {
        expect(en.label.clearAll).toBe(en.label.confirmClearAction);
        expect(fr.label.clearAll).toBe(fr.label.confirmClearAction);
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
