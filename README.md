# lucuma-graphql-routes

This provides GraphQL routing via http (queries/mutations only), and via the Atom-based WebSocket protocol (all). This project will probably become a part of the [Clue](https://github.com/gemini-hlsw/clue) project, which provides a GraphQL client for the above protocols.

```scala
libraryDependencies += "edu.gemini" %% "lucuma-graphql-routes-sangria" % <version> // Sangria
libraryDependencies += "edu.gemini" %% "lucuma-graphql-routes-grackle" % <version> // Grackle
```

The `HttpRoutes` provided by this library will delegate GraphQL operation to a `GraphQLService` which is computed on a per-request basis. This allows the application to select a different schema based on credentials in the request, for example.

So first, write a method that constructs a `GraphQLService` based on the incoming `Authorization` header (if any).

```scala
def mkService(auth: Option[Authorization]): F[Option[GraphQLService[F]]] =
  // Yield None to deny access (403 Forbidden), or a GraphQLService if it's
  // ok to service the request.
```

There are two constructors for `GraphQLService`, depending on the back end you're using.

```scala
new SangriaGraphQLService[F](mySchema, userData, exceptionHandler) // Sangria
new GrackleGraphQLService[F](myMapping) // Grackle
```

Next construct the `HttpRoutes`, passing the method defined above.

```scala
Routes.forService(mkService) // HttpRoutes[F]
```

The resulting `HttpRoutes` will serve the following endpoints:

- `Root / "graphql"` using the [Serving over HTTP](https://graphql.org/learn/serving-over-http/) specification.
- `Root / "ws"` using the [GraphQL over WebSocket Protocol](https://github.com/apollographql/subscriptions-transport-ws/blob/master/PROTOCOL.md) specification.

The `"graphql"` and `"ws"` segments are defaults; you can specify different values when you call `Routes.forService`.
