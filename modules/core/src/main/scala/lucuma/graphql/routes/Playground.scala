// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

object Playground {

  def apply(
    graphQLPath: String,
    wsPath:      String,
  ): String =
    // https://github.com/graphql/graphiql/blob/main/examples/graphiql-cdn/index.html
    s"""|<!DOCTYPE html>
        |<html lang="en">
        |  <head>
        |    <meta charset="UTF-8" />
        |    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
        |    <title>GraphQL Playground</title>
        |    <style>
        |      body {
        |        height: 100%;
        |        margin: 0;
        |        width: 100%;
        |        overflow: hidden;
        |      }
        |
        |      #graphiql {
        |        height: 100vh;
        |      }
        |    </style>
        |    <!--
        |           This GraphiQL example depends on Promise and fetch, which are available in
        |           modern browsers, but can be "polyfilled" for older browsers.
        |           GraphiQL itself depends on React DOM.
        |           If you do not want to rely on a CDN, you can host these files locally or
        |           include them directly in your favored resource bundler.
        |         -->
        |    <script
        |      crossorigin
        |      src="https://unpkg.com/react@18/umd/react.development.js"
        |    ></script>
        |    <script
        |      crossorigin
        |      src="https://unpkg.com/react-dom@18/umd/react-dom.development.js"
        |    ></script>
        |    <!--
        |           These two files can be found in the npm module, however you may wish to
        |           copy them directly into your environment, or perhaps include them in your
        |           favored resource bundler.
        |          -->
        |    <script
        |      src="https://unpkg.com/graphiql/graphiql.min.js"
        |      type="application/javascript"
        |    ></script>
        |    <link rel="stylesheet" href="https://unpkg.com/graphiql/graphiql.min.css" />
        |    <!-- 
        |           These are imports for the GraphIQL Explorer plugin.
        |          -->
        |    <script
        |      src="https://unpkg.com/@graphiql/plugin-explorer/dist/index.umd.js"
        |      crossorigin
        |    ></script>
        |
        |    <link
        |      rel="stylesheet"
        |      href="https://unpkg.com/@graphiql/plugin-explorer/dist/style.css"
        |    />
        |  </head>
        |  <body>
        |    <div id="graphiql">Loading...</div>
        |    <noscript>You need to enable JavaScript to run the playground.</noscript>
        |    <script>
        |      const root = ReactDOM.createRoot(document.getElementById("graphiql"));
        |      const fetcher = GraphiQL.createFetcher({
        |        url: "/$graphQLPath",
        |        subscriptionUrl: "/$wsPath",
        |      });
        |      const explorerPlugin = GraphiQLPluginExplorer.explorerPlugin();
        |      root.render(
        |        React.createElement(GraphiQL, {
        |          fetcher,
        |          defaultEditorToolsVisibility: true,
        |          plugins: [explorerPlugin],
        |          defaultHeaders: '{ Authorization: "Bearer yourTokenHere" }',
        |          shouldPersistHeaders: true,
        |        })
        |      );
        |    </script>
        |  </body>
        |</html>
        |""".stripMargin

}
