# BlueAlbum frontend

This directory contains the Vue 3 + Vite frontend. The instructions below cover
local development and frontend checks; they do not establish backend integration,
production readiness, or a production performance target.

## Prerequisites

- Node.js 22.13 or newer (Node.js 24 is used by CI and the frontend Docker build)
- pnpm 11.5.0 (the version declared by `package.json`)
- A running BlueAlbum gateway when using the local API proxy

Install dependencies from this directory:

```sh
pnpm install --frozen-lockfile
```

## Local environment

Use the local example as a starting point:

```sh
cp .env.local.example .env.local
# PowerShell: Copy-Item .env.local.example .env.local
```

[.env.local.example](.env.local.example) uses the local-safe defaults:

- `VITE_API_BASE_URL=/api/v1`, with API calls proxied by Vite to
  `http://127.0.0.1:9000`;
- `VITE_USE_API_MOCK=false`;
- human verification disabled for local development; and
- external OAuth buttons disabled until a provider is configured.

Do not put real provider secrets or test credentials in a tracked file. The
Vite proxy target is defined in [`vite.config.ts`](vite.config.ts), so a local
gateway must listen on port 9000 for the relative API URL above to work.

## Start the frontend

For an isolated full-stack local run, first install the frontend dependencies,
then run the repository-level launcher from the repository root:

```powershell
pwsh -File ./scripts/Start-Local.ps1
```

The launcher requires a working JDK 21+, Docker Desktop, Maven, and Node.js. It
starts the backend, notification service, gateway, and Vite server, and creates
random local secrets under its local runtime directory. It already owns port
5173, so do not start a second `pnpm dev` process. Stop that run with:

```powershell
pwsh -File ./scripts/Stop-Local.ps1
```

See [`scripts/Start-Local.ps1`](../scripts/Start-Local.ps1) and
[`scripts/Stop-Local.ps1`](../scripts/Stop-Local.ps1) for the actual launcher
and shutdown behavior.

If the backend and gateway are already running by another method, start only
the frontend from this directory:

```sh
pnpm dev
```

Open <http://localhost:5173>. The development server listens on `127.0.0.1:5173`
and proxies both `/api/v1/**` and `/uploads/**` to the gateway. The optional
repository smoke runner is [`scripts/Test-Local.ps1`](../scripts/Test-Local.ps1);
it exercises the full local stack and creates local test data, so it is not a
frontend-only check.

## Frontend validation

`pnpm build` also checks the generated build manifest: the application shell,
Discover and Sections list must keep mathematical typesetting and syntax
highlighting outside their static dependencies, while the create/detail routes
remain lazy. It prints JavaScript byte and gzip totals for these entry points;
these totals describe build artifacts, not measured load times or Core Web Vitals.

Run these commands from `frontend/`:

```sh
pnpm lint
pnpm run type-check
pnpm test
pnpm run build
```

`pnpm lint` checks Vue templates, JavaScript and TypeScript using ESLint. It is a
separate check from type checking and compilation; the shared alias configuration
for Vite and Vitest is in `config/paths.ts`.

`pnpm test` runs the Vitest suite once. `pnpm run build` performs a Vite
production build check; it intentionally fails when `VITE_USE_API_MOCK=true` is
set for the build. Passing these commands only verifies the frontend package
and its configured build/test checks.
