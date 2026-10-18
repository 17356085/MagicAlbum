# Temporary dependency patches

`braces@3.0.3.patch` bounds parser AST nesting to 100 levels before recursive
compile/expand walkers execute. It covers brace and parenthesis nesting; normal
repository file globs remain supported. The original package license is unchanged.

Reason: [GHSA-vfj7-8cjw-p6xm](https://github.com/micromatch/braces/issues/70).
On 2026-10-18 the official npm registry still publishes 3.0.3 as the newest
release, although the advisory names 3.0.4 as the patched range. The lockfile
records this local patch. Do not silence the advisory or replace it with an
unpublished version. Version-based audit still reports one high development
dependency advisory; it does not inspect the local patch.

Regression tests resolve the actual Tailwind → micromatch → braces dependency,
check normal alternatives, and reject 4,000-level brace and parenthesis input
with a controlled SyntaxError. The final CSS is byte-identical to the build
before the selector-parser update and this patch.

Remove the patch after a published upstream release contains an equivalent
depth bound, then rerun the dependency regression, full frontend tests, type
checks, build and audit. Patch configuration lives in `pnpm-workspace.yaml`;
`pnpm install --frozen-lockfile` applies and verifies it in CI.
