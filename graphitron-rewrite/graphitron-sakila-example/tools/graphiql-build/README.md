# GraphiQL build

One-shot Vite build for the GraphiQL playground served by `graphitron-sakila-example`. The output (a small set of static files) is committed under [`../../src/main/resources/META-INF/resources/graphiql/`](../../src/main/resources/META-INF/resources/graphiql/), where Quarkus serves it as a static asset at `http://localhost:8080/graphiql/`.

**The Maven build does not invoke node.** This directory exists so the build is reproducible when upgrading GraphiQL or React. CI never touches it.

## Rebuild

```bash
cd graphitron-rewrite/graphitron-sakila-example/tools/graphiql-build
npm install
npm run build
```

`npm run build` writes directly into `../../src/main/resources/META-INF/resources/graphiql/`, replacing the previous artifacts. Commit the diff alongside any version bumps in `package.json`.

## When to rerun

- Bumping `graphiql`, `react`, `react-dom`, `graphql`, or `@graphiql/toolkit` in `package.json`.
- Changing `src/main.jsx` (e.g. wiring extra GraphiQL plugins).
- Changing `index.html` or `vite.config.js`.

The committed output should always be in sync with the inputs in this directory; the diff under `META-INF/resources/graphiql/` is the artifact, the diff here is the recipe.
