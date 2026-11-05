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
