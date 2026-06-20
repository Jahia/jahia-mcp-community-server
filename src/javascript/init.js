import {registry} from '@jahia/ui-extender';
import register from './CommunityMcpConfig/register';
import i18next from 'i18next';

export default function () {
    registry.add('callback', 'jahia-mcp-community-server', {
        targets: ['jahiaApp-init:50'],
        callback: async () => {
            await i18next.loadNamespaces('jahia-mcp-community-server');
            register();
        }
    });
}
