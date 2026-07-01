import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { resolve } from 'node:path';

// Builds the self-hosted GraphiQL bundle into
// ../../src/main/resources/no/sikt/graphitron/jakarta/rest/graphiql/, committed as plain
// classpath resources and streamed by GraphqlResource's asset endpoint (no META-INF/resources/,
// which would only work on Quarkus). This build runs only when bumping GraphiQL/React versions;
// the Maven build never invokes node.
export default defineConfig({
  plugins: [react()],
  // Relative asset URLs so dynamic chunk/worker imports resolve against the entry module's own
  // URL. GraphqlResource serves the entry from .../graphql/assets/graphiql.js, so its relative
  // ./chunk-*.js imports land back on the same assets endpoint regardless of the mount path.
  base: './',
  build: {
    // The graphiql.html shell lives in the parent `rest/` package, not here, so emptyOutDir is
    // safe: it only clears this bundle directory between builds.
    outDir: resolve(import.meta.dirname, '../../src/main/resources/no/sikt/graphitron/jakarta/rest/graphiql'),
    emptyOutDir: true,
    assetsDir: '',
    sourcemap: false,
    minify: 'esbuild',
    // Bundle all CSS into one file so the shell can reference a single fixed graphiql.css;
    // otherwise Vite emits per-chunk CSS (graphiql.css + graphiql2.css + ...) and the shell
    // would have to track a moving set of stylesheet names.
    cssCodeSplit: false,
    rollupOptions: {
      // JS-entry build (not HTML-driven): no index.html is emitted, since the hand-written
      // graphiql.html shell is served by GraphqlResource instead.
      input: resolve(import.meta.dirname, 'src/main.jsx'),
      output: {
        // Fixed names for the two files the shell references directly; hashed names for the
        // code-split chunks, workers, and monaco fonts (served via the [A-Za-z0-9._-]+ allowlist).
        entryFileNames: 'graphiql.js',
        chunkFileNames: '[name]-[hash].js',
        assetFileNames: (asset) =>
          asset.names?.some((n) => n.endsWith('.css')) ? 'graphiql.css' : '[name]-[hash][extname]',
      },
    },
  },
  // monaco-graphql's worker uses dynamic imports (code-splitting), which Vite's default IIFE
  // worker output can't handle. ES modules support code-splitting natively.
  worker: { format: 'es' },
});
