# GraphiQL build

One-shot Vite build for the self-hosted GraphiQL playground served by `graphitron-jakarta-rest`. The output (a version-pinned GraphiQL 5 + React bundle) is committed under [`../../src/main/resources/no/sikt/graphitron/jakarta/rest/graphiql/`](../../src/main/resources/no/sikt/graphitron/jakarta/rest/graphiql/), where `GraphqlResource` streams it from its `assets/{name}` endpoint. The committed bundle is the artifact consumers depend on; this recipe is the receipt that reproduces it.

**The Maven build does not invoke node.** This directory exists so the bundle is reproducible when upgrading GraphiQL or React. There is no `<build>` binding, no exec-maven or frontend plugin; CI never touches node.

## Rebuild

```bash
cd graphitron-jakarta-rest/tools/graphiql-build
npm install
npm run build
```

`npm run build` clears and rewrites `../../src/main/resources/no/sikt/graphitron/jakarta/rest/graphiql/` (the `graphiql.html` shell lives in the parent `rest/` package and is untouched). Commit the diff alongside any version bumps in `package.json`.

## Version pinning

`package.json` pins **exact** versions (no `^` ranges) for `graphiql`, `react`, `react-dom`, `graphql`, `@graphiql/toolkit`, `vite`, and `@vitejs/plugin-react`, and `package-lock.json` is committed. This keeps the runtime playground reproducible: the served assets change only through a deliberate bump-and-rebuild commit, never by silently tracking an upstream `latest`.

## When to rerun

- Bumping `graphiql`, `react`, `react-dom`, `graphql`, or `@graphiql/toolkit` in `package.json`.
- Changing `src/main.jsx` (e.g. wiring extra GraphiQL plugins).
- Changing `vite.config.js`.

The committed output should always be in sync with the inputs in this directory; the diff under `graphiql/` is the artifact, the diff here is the recipe.

## Output layout

The build emits fixed-name entry files (`graphiql.js`, `graphiql.css`) that the shell references directly, plus hash-named code-split chunks, monaco web workers, and the codicon font. `vite.config.js` uses `base: './'` so every chunk, worker, and font resolves relative to the entry files' served URL; only the two entry files need the absolute `{{ASSET_BASE}}` prefix that `GraphqlResource` injects into `graphiql.html` at serve time.
