import fs from 'fs';
import path from 'path';

const scss = fs.readFileSync(path.join(__dirname, '../CommunityMcpConfig.scss'), 'utf8');
const block = selector => {
    const start = scss.indexOf(`${selector} {`);
    return scss.slice(start, scss.indexOf('}', start));
};

describe('save bar layout', () => {
    // WCAG 2.4.12 (AAA): a sticky bar would cover the focused control near the bottom of the
    // viewport, because browsers scroll focus into view without accounting for sticky boxes.
    test('the save bar is not sticky or fixed, so it never hides the focused element', () => {
        const actions = block('.mcp_actions');
        expect(actions).not.toBe('');
        expect(actions).not.toMatch(/position:\s*(sticky|fixed)/);
    });
});

describe('visual presentation (WCAG 1.4.8, AAA)', () => {
    test('tree descriptions and notes are at most 80 characters wide (A11Y-2)', () => {
        const rule = block('.mcp_operationDesc,\n.mcp_note,\n.mcp_noteError');
        expect(rule).toMatch(/max-width:\s*80ch/);
    });

    test('paragraph spacing is at least 1.5 times the line spacing (A11Y-3)', () => {
        // Line-height 1.5 at 14px = 21px; the step to the next paragraph must be >= 31.5px,
        // so the margin must be >= 10.5px: 1em (14px) gives 35px.
        expect(block('.mcp_detailsText')).toMatch(/margin:\s*0 0 1em/);
        expect(block('.mcp_alertText')).toMatch(/margin:\s*0 0 1em/);
    });
});

describe('disclosure and focus styles', () => {
    test('a <summary> laid out with flex draws its own open/closed marker (A11Y-4)', () => {
        expect(block('.mcp_summary')).toMatch(/list-style:\s*none/);
        expect(scss).toMatch(/\.mcp_summary::before\s*{[^}]*border-style:\s*solid/);
        expect(scss).toMatch(/\.mcp_details\[open\] > \.mcp_summary::before\s*{[^}]*rotate\(90deg\)/);
    });

    test('a heading that receives focus programmatically has an author focus ring (A11Y-8)', () => {
        expect(scss).toMatch(/\.mcp_panelTitle:focus-visible\s*{[^}]*outline:\s*3px solid \$focus/);
    });
});
