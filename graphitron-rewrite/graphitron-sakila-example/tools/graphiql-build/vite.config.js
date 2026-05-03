import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { resolve } from 'node:path';

// Vite emits the GraphiQL bundle into ../../src/main/resources/META-INF/resources/graphiql/.
// Quarkus serves META-INF/resources/* as static assets, so the result is reachable at
// http://localhost:8080/graphiql/. This build runs only when bumping GraphiQL/React versions;
// the Maven build never invokes node.
export default defineConfig({
  plugins: [react()],
  // Relative asset URLs in index.html so the bundle is mountable at any URL prefix
  // (Quarkus could later move /graphiql/ behind a non-root context path without a rebuild).
  base: './',
  build: {
    outDir: resolve(import.meta.dirname, '../../src/main/resources/META-INF/resources/graphiql'),
    emptyOutDir: true,
    sourcemap: false,
    minify: 'esbuild',
  },
});
