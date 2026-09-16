// @ts-check
import eslint from '@eslint/js';
import tseslint from 'typescript-eslint';

export default tseslint.config(
  {
    // Build output and dependencies are never linted.
    ignores: ['lib/**', 'node_modules/**'],
  },
  eslint.configs.recommended,
  // Type-aware rules: docs/16_CODING_STANDARDS.md §9 forbids `any` on business paths,
  // which only the type-checked rule set can actually detect.
  tseslint.configs.strictTypeChecked,
  tseslint.configs.stylisticTypeChecked,
  {
    languageOptions: {
      parserOptions: {
        projectService: true,
        tsconfigRootDir: import.meta.dirname,
      },
    },
    rules: {
      '@typescript-eslint/no-explicit-any': 'error',
      '@typescript-eslint/explicit-module-boundary-types': 'error',
      '@typescript-eslint/consistent-type-imports': 'error',
      // Unused args are allowed only when explicitly marked, e.g. `_request`.
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_' }],
      // Coordinates/photo paths must never reach stdout; use firebase-functions logger.
      'no-console': 'error',
      eqeqeq: ['error', 'always'],
    },
  },
  {
    files: ['**/*.test.ts'],
    rules: {
      // Tests deliberately feed malformed values into the parsers under test.
      '@typescript-eslint/no-unsafe-argument': 'off',
      // node:test `describe`/`it` return promises the runner owns; callers must not await.
      '@typescript-eslint/no-floating-promises': 'off',
    },
  },
  {
    files: ['eslint.config.mjs'],
    extends: [tseslint.configs.disableTypeChecked],
  },
);
