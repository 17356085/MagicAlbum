import eslint from '@eslint/js'
import globals from 'globals'
import tseslint from 'typescript-eslint'
import vue from 'eslint-plugin-vue'

const vueParser = vue.configs['flat/essential'].find(
  (config) => config.languageOptions?.parser,
).languageOptions.parser

const typescriptRules = {
  '@typescript-eslint/ban-ts-comment': 'error',
  '@typescript-eslint/no-explicit-any': 'error',
  '@typescript-eslint/no-unused-vars': [
    'error',
    {
      argsIgnorePattern: '^_',
      caughtErrorsIgnorePattern: '^_',
      varsIgnorePattern: '^_',
    },
  ],
}

export default tseslint.config(
  {
    ignores: [
      '**/.cache/**',
      '**/.playwright-browsers/**',
      '**/.tlo/**',
      '**/.vite/**',
      '**/coverage/**',
      '**/dist/**',
      '**/node_modules/**',
      '**/playwright-report/**',
      '**/test-results/**',
    ],
  },
  eslint.configs.recommended,
  ...vue.configs['flat/essential'],
  ...tseslint.configs.recommended,
  {
    files: ['src/**/*.{ts,tsx,vue}'],
    languageOptions: {
      globals: globals.browser,
      parserOptions: {
        ecmaVersion: 'latest',
        extraFileExtensions: ['.vue'],
        parser: tseslint.parser,
        sourceType: 'module',
      },
    },
    rules: {
      ...typescriptRules,
      'no-unused-vars': 'off',
      'no-empty': ['error', { allowEmptyCatch: true }],
      'vue/no-unused-vars': 'error',
    },
  },
  {
    files: ['**/*.vue'],
    languageOptions: {
      parser: vueParser,
      parserOptions: {
        parser: tseslint.parser,
      },
    },
    rules: {
      'vue/multi-word-component-names': 'off',
    },
  },
  {
    files: ['src/**/*.{spec,test}.{js,jsx,ts,tsx,vue}', 'tests/**/*.{js,jsx,ts,tsx,vue}'],
    languageOptions: {
      globals: {
        ...globals.browser,
        ...globals.node,
        afterAll: 'readonly',
        afterEach: 'readonly',
        beforeAll: 'readonly',
        beforeEach: 'readonly',
        describe: 'readonly',
        expect: 'readonly',
        it: 'readonly',
        test: 'readonly',
        vi: 'readonly',
      },
    },
  },
  {
    files: [
      'config/**/*.{js,mjs,cjs,ts}',
      'eslint.config.mjs',
      '**/*.config.cjs',
      'scripts/**/*.{js,mjs,cjs}',
      'vite.config.{js,mjs,ts}',
      'vitest.config.{js,mjs,ts}',
    ],
    languageOptions: {
      globals: globals.node,
    },
  },
  {
    files: ['**/*.cjs'],
    rules: {
      '@typescript-eslint/no-require-imports': 'off',
    },
  },
  {
    files: ['**/*.{ts,tsx,vue}'],
    rules: {
      'no-empty': ['error', { allowEmptyCatch: true }],
    },
  },
)
