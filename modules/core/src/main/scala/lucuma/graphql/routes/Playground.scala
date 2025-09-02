// Copyright (c) 2016-2025 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

object Playground {

  def apply(
    graphQLPath: String,
    wsPath:      String
  ): String =
    // https://github.com/graphql/graphiql/tree/main/examples/graphiql-cdn
    s"""|<!DOCTYPE html>
        |<html lang="en">
        |  <head>
        |    <meta charset="UTF-8" />
        |    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
        |    <title>GraphiQL Explorer</title>
        |    <style>
        |      body {
        |        margin: 0;
        |      }
        |
        |      #graphiql {
        |        height: 100dvh;
        |      }
        |
        |      .loading {
        |        font-family: sans-serif;
        |        height: 100%;
        |        display: flex;
        |        align-items: center;
        |        justify-content: center;
        |        font-size: 4rem;
        |      }
        |    </style>
        |    <link rel="stylesheet" href="https://esm.sh/graphiql/dist/style.css" />
        |    <link
        |      rel="stylesheet"
        |      href="https://esm.sh/@graphiql/plugin-explorer/dist/style.css"
        |    />
        |    <!-- 
        |    Note:
        |    The ?standalone flag bundles the module along with all of its `dependencies`, excluding `peerDependencies`, into a single JavaScript file.
        |    `@emotion/is-prop-valid` is a shim to remove the console error ` module "@emotion /is-prop-valid" not found`. Upstream issue: https://github.com/motiondivision/motion/issues/3126 -->
        |    <script type="importmap">
        |      {
        |        "imports": {
        |          "react": "https://esm.sh/react@19.1.1",
        |          "react/": "https://esm.sh/react@19.1.1/",
        |
        |          "react-dom": "https://esm.sh/react-dom@19.1.1",
        |          "react-dom/": "https://esm.sh/react-dom@19.1.1/",
        |
        |          "graphiql": "https://esm.sh/graphiql?standalone&external=react,react-dom,@graphiql/react,graphql",
        |          "graphiql/": "https://esm.sh/graphiql/",
        |          "@graphiql/plugin-explorer": "https://esm.sh/@graphiql/plugin-explorer?standalone&external=react,@graphiql/react,graphql",
        |          "@graphiql/react": "https://esm.sh/@graphiql/react?standalone&external=react,react-dom,graphql,@graphiql/toolkit,@emotion/is-prop-valid",
        |
        |          "@graphiql/toolkit": "https://esm.sh/@graphiql/toolkit?standalone&external=graphql",
        |          "graphql": "https://esm.sh/graphql@16.11.0",
        |          "graphql-ws": "https://esm.sh/graphql-ws@5.6.0",
        |
        |          "@emotion/is-prop-valid": "data:text/javascript,"
        |        }
        |      }
        |    </script>
        |    <script type="module">
        |      import React from 'react';
        |      import ReactDOM from 'react-dom/client';
        |      import { GraphiQL, HISTORY_PLUGIN } from 'graphiql';
        |      import { createGraphiQLFetcher } from '@graphiql/toolkit';
        |      import { explorerPlugin } from '@graphiql/plugin-explorer';
        |      import { createClient } from 'graphql-ws';
        |      import 'graphiql/setup-workers/esm.sh';
        |
        |      const fetcher = createGraphiQLFetcher({
        |        url: '$graphQLPath',
        |        wsClient: createClient({ url: '$wsPath' })
        |      });
        |      const plugins = [HISTORY_PLUGIN, explorerPlugin()];
        |
        |      function App() {
        |        return React.createElement(GraphiQL, {
        |          fetcher,
        |          plugins,
        |          defaultEditorToolsVisibility: true,
        |          shouldPersistHeaders: true,
        |        });
        |      }
        |
        |      const container = document.getElementById('graphiql');
        |      const root = ReactDOM.createRoot(container);
        |      root.render(React.createElement(App));
        |    </script>
        |  </head>
        |  <body>
        |    <div id="graphiql">
        |      <div class="loading">Loading...</div>
        |    </div>
        |  </body>
        |</html>
        |""".stripMargin

}
