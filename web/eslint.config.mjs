import { defineConfig, globalIgnores } from "eslint/config";
import nextVitals from "eslint-config-next/core-web-vitals";
import nextTs from "eslint-config-next/typescript";
import prettier from "eslint-config-prettier/flat";
import jsxA11y from "eslint-plugin-jsx-a11y";

import zeitwide from "./eslint-rules/link-text.mjs";

const eslintConfig = defineConfig([
  ...nextVitals,
  ...nextTs,
  {
    rules: {
      eqeqeq: ["error", "always"],
      "no-console": ["error", { allow: ["warn", "error"] }],
      "@typescript-eslint/consistent-type-imports": "error",
      "@typescript-eslint/no-explicit-any": "error",
    },
  },
  // eslint-config-next registers the jsx-a11y plugin; turn on its full recommended set.
  { files: ["**/*.tsx"], rules: jsxA11y.flatConfigs.recommended.rules },
  // Link text states its fact; "here", "read more", "learn more" and "click" fail CI.
  {
    files: ["**/*.tsx"],
    plugins: { zeitwide },
    rules: { "zeitwide/link-text": "error" },
  },
  prettier,
  // src/api/schema.ts is generated from api/openapi.yaml.
  globalIgnores([
    ".next/**",
    "out/**",
    "build/**",
    "coverage/**",
    "next-env.d.ts",
    "src/api/schema.ts",
    "playwright-report/**",
    "test-results/**",
  ]),
]);

export default eslintConfig;
