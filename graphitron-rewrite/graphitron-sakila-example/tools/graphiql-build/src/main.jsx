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

const fetcher = createGraphiQLFetcher({ url: '/graphql' });
const root = createRoot(document.getElementById('graphiql'));
root.render(<GraphiQL fetcher={fetcher} defaultEditorToolsVisibility="variables" />);
