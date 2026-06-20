// Jest configuration for the React admin UI unit tests.
// Webpack handles the production build; this config is used only by `yarn test`.
module.exports = {
    testEnvironment: 'jsdom',
    rootDir: '.',
    roots: ['<rootDir>/src/javascript'],
    testMatch: ['**/__tests__/**/*.test.{js,jsx}', '**/*.test.{js,jsx}'],
    setupFilesAfterEnv: ['<rootDir>/jest.setup.js'],
    transform: {
        // Isolated babel-jest so the project's webpack babel options never leak in.
        '^.+\\.(js|jsx)$': ['babel-jest', {
            configFile: false,
            babelrc: false,
            presets: [
                ['@babel/preset-env', {targets: {node: 'current'}}],
                ['@babel/preset-react', {runtime: 'automatic'}]
            ]
        }]
    },
    moduleNameMapper: {
        // SCSS modules → identity proxy so `styles.foo` returns the string 'foo'
        '\\.(scss|css)$': 'identity-obj-proxy',
        // Mocks for libraries that are not needed for unit logic / are ESM-only
        '^@jahia/moonstone$': '<rootDir>/src/javascript/__mocks__/moonstone.js',
        '^react-i18next$': '<rootDir>/src/javascript/__mocks__/react-i18next.js',
        '^@apollo/client$': '<rootDir>/src/javascript/__mocks__/apollo-client.js'
    },
    clearMocks: true
};
