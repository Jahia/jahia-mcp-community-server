import {registry} from '@jahia/ui-extender';
import {CommunityMcpConfigAdmin} from './CommunityMcpConfig';
import React from 'react';

export default () => {
    registry.add('adminRoute', 'jahiaMcpCommunityServer', {
        targets: ['administration-server-configuration:50'],
        requiredPermission: 'admin',
        label: 'jahia-mcp-community-server:label.menu_entry',
        isSelectable: true,
        render: () => React.createElement(CommunityMcpConfigAdmin)
    });
};
