# lucuma-graphql-routes

This provides GraphQL routing via http (queries/mutations only), and via the Atom-based WebSocket protocol (all). This project will probably become a part of the [Clue](https://github.com/gemini-hlsw/clue) project, which provides a GraphQL client for the above protocols.

```scala
libraryDependencies += "edu.gemini" %% "lucuma-graphql-routes-sangria" % <version> // Sangria
libraryDependencies += "edu.gemini" %% "lucuma-graphql-routes-grackle" % <version> // Grackle
```

First construct a `GraphQLService`.

```scala
val service = new SangriaGraphQLService[F](mySchema, userData, exceptionHandler) // Sangria
val service = new GrackleGraphQLService[F](myMapping) // Grackle
```

Next, construct an `HttpRoutes` for the service.

```scala
Routes.forService[F](service, ssoClient)
```

The resulting `HttpRoutes` will serve the following endpoints:

- `Root / "graphql"` using the [Serving over HTTP](https://graphql.org/learn/serving-over-http/) specification.
- `Root / "ws"` using the [GraphQL over WebSocket Protocol](https://github.com/apollographql/subscriptions-transport-ws/blob/master/PROTOCOL.md) specification.

You can customize the `"graphql"` and `"ws"` segments when you call `Routes.forService`.

## Notes

The `SSOClient` required by `Routes.forService` is only used for logging and will probably go away.
