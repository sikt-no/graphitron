import React from 'react';
import { createRoot } from 'react-dom/client';
import { GraphiQL } from 'graphiql';
import { createGraphiQLFetcher } from '@graphiql/toolkit';
// Side-effect: registers globalThis.MonacoEnvironment.getWorker via Vite's ?worker imports
// so monaco-editor's JSON / GraphQL / generic-editor language services run in web workers.
// Without this, monaco's foreign-module loader fails with "can't access property toUrl" when
// it tries to spin up a worker.
import 'graphiql/setup-workers/vite';
import 'graphiql/style.css';

// Post to the exact path the playground is served from, so the fetcher stays correct wherever
// the consumer mounts GraphqlResource (/graphql, /api/graphql, ...). GraphqlResource serves the
// page at the GraphQL endpoint itself, so origin + pathname is that endpoint.
const fetcher = createGraphiQLFetcher({ url: window.location.origin + window.location.pathname });
const root = createRoot(document.getElementById('graphiql'));
root.render(<GraphiQL fetcher={fetcher} defaultEditorToolsVisibility="variables" />);
