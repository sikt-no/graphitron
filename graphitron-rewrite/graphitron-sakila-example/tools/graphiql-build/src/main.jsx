import React from 'react';
import { createRoot } from 'react-dom/client';
import { GraphiQL } from 'graphiql';
import { createGraphiQLFetcher } from '@graphiql/toolkit';
import 'graphiql/style.css';

const fetcher = createGraphiQLFetcher({ url: '/graphql' });
const root = createRoot(document.getElementById('graphiql'));
root.render(<GraphiQL fetcher={fetcher} defaultEditorToolsVisibility="variables" />);
