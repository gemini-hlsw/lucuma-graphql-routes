# lucuma-graphql-routes

This provides GraphQL routing via http (queries/mutations only), and via the Atom-based WebSocket protocol (all). This project will probably become a part of the [Clue](https://github.com/gemini-hlsw/clue) project, which provides a GraphQL client for the above protocols.

```scala
libraryDependencies += "edu.gemini" %% "lucuma-graphql-routes" % <version>
```

The `HttpRoutes` that this library builds run one `GraphQLService` for the life of the server. An `Authenticator` turns the credentials of a request into an `Auth` result. Build the service once, build the authenticator, and pass both to `Routes.forService`.

A service with no authentication:

```scala
for
  service <- GraphQLService[F](myMapping)
yield (wsb: WebSocketBuilder2[F]) => Routes.forOpenService(service, wsb)
```

A service that authenticates with a bearer token, and that lets an anonymous client read the schema:

```scala
val authenticator: Authenticator[F] =
  Authenticator.fromOptionF(_.flatTraverse(ssoClient.get).map(_.map(u => RequestContext(Env("user" -> u)))))

for
  service <- GraphQLService[F](myMapping)
  auth    <- authenticator.cached(5.minutes)
yield (wsb: WebSocketBuilder2[F]) => Routes.forService(service, auth, wsb)
```

If the client sends no token, the routes serve the schema and refuse every other request. Set `RoutesConfig.anonymous` to change this policy.

A mapping reads the user of the request out of the env:

```scala
override val selectElaborator = SelectElaborator:
  case (QueryType, "programs", Nil) =>
    Elab.envE[User]("user").flatMap(user => Elab.transformChild(filterForUser(user)))
```

Put the user in the env of the `RequestContext` that the authenticator returns. The routes pass that env to `compiler.compile` and to `interpreter.run`.

The resulting `HttpRoutes` will serve the following endpoints:

- `Root / "graphql"` using the [Serving over HTTP](https://graphql.org/learn/serving-over-http/) specification.
- `Root / "ws"` using the [GraphQL over WebSocket Protocol](https://github.com/apollographql/subscriptions-transport-ws/blob/master/PROTOCOL.md) specification.
- `Root / "playground.html"` serving the [GraphQL Playground](https://github.com/graphql/graphql-playground) HTML application.

The `"graphql"`, `"ws"`, and `"playground.html"` segments are defaults; you can specify different values when you call `Routes.forService`.

## Migration from 0.15

`Routes.forService` no longer takes a function that builds a `GraphQLService` for each request. It
takes one service, which you build once, and an `Authenticator`.

| 0.15                                                                  | 0.16                                                                                       |
| --------------------------------------------------------------------- | ------------------------------------------------------------------------------------------ |
| `service: Option[Authorization] => F[Option[GraphQLService[F]]]`      | `service: GraphQLService[F]` and `authenticator: Authenticator[F]`                         |
| `GraphQLService(mapping, props*)`                                     | `GraphQLService[F](mapping)`, which gives `F[GraphQLService[F]]` and validates the mapping |
| A `None` result, which gave status 403                                | `Auth.Denied(message)`, or `Auth.Anonymous` with `AnonymousPolicy.Deny`                    |
| Your own introspection-only mapping for a client with no credentials  | `AnonymousPolicy.IntrospectionOnly`, which is the default                                  |
| The `props` parameter of `GraphQLService`                             | `RequestContext.attributes`                                                                |
| `graphQLPath`, `wsPath`, `playgroundPath`, and `keepAlive` parameters | the fields of `RoutesConfig`                                                               |

A mapping that took the user as a constructor parameter now reads it from the env that the routes
pass to `compile` and to `interpreter.run`. Put the user in the env of the `RequestContext` that
your authenticator returns.

## Tracing

This library uses [otel4s](https://typelevel.org/otel4s/) for OpenTelemetry tracing. A `Tracer[F]` must be in scope when constructing routes. Each operation produces a `graphql` span with `graphql.document`, `graphql.operation.type`, and `graphql.operation.name` semconv attributes.

Trace context propagation differs by transport:

- **HTTP (queries/mutations)**: The standard `traceparent` HTTP header is propagated automatically by the http4s otel4s middleware in the calling application.
- **WebSocket subscriptions**: The WebSocket endpoint uses the [graphql-transport-ws](https://github.com/graphql/graphql-over-http/blob/main/rfcs/GraphQLOverWebSocket.md) protocol. If the client includes a W3C `traceparent` in the `extensions` field of the `subscribe` message, this library extracts it and re-parents the server span on the client's remote trace context, enabling end-to-end distributed tracing per subscription operation.
