// Minimal @jahia/moonstone mock for unit tests.
// Renders plain DOM stand-ins so component logic can be asserted without the
// real (ESM-heavy, style-dependent) component library.
import React from 'react';

export const Button = ({label, onClick, isDisabled}) =>
    React.createElement('button', {type: 'button', disabled: isDisabled, onClick}, label);

export const Loader = () => React.createElement('div', {'data-testid': 'loader'});

export const Typography = ({children, className}) =>
    React.createElement('span', {className}, children);
