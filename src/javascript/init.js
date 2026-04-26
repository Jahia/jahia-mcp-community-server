import {registry} from '@jahia/ui-extender';
import register from './McpConfig/register';
import i18next from 'i18next';

export default function () {
    registry.add('callback', 'jahia-mcp-community-server', {
        targets: ['jahiaApp-init:50'],
        callback: async () => {
            await i18next.loadNamespaces('jahia-mcp-community-server', () => {
                console.debug('%c jahia-mcp-community-server: i18n namespace loaded', 'color: #006633');
            });
            register();
            console.debug('%c jahia-mcp-community-server: activation completed', 'color: #006633');
        }
    });
}
