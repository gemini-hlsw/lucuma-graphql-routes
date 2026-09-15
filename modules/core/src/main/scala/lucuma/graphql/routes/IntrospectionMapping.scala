// Copyright (c) 2016-2026 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.MonadThrow
import grackle.Mapping
import grackle.QueryCompiler.Elab
import grackle.QueryCompiler.SelectElaborator
import grackle.Schema
import grackle.TypeRef

/**
 * Read-only mapping for schema introspection. Routes uses it for anonymous clients under
 * `IntrospectionOnly` policy. Grackle answers introspection queries itself, so the type mappings
 * are empty on purpose.
 */
private[routes] object IntrospectionMapping:

  def apply[F[_]: MonadThrow](loadedSchema: Schema): Mapping[F] =
    new Mapping[F]:
      val M: MonadThrow[F] = MonadThrow[F]
      val schema: Schema   = loadedSchema

      private val rootTypes: List[TypeRef] =
        (List(schema.queryType) ++ schema.mutationType ++ schema.subscriptionType)
          .map(t => schema.ref(t.name))

      val typeMappings: TypeMappings =
        TypeMappings.unchecked(rootTypes.map(ObjectMapping(_, Nil))*)

      override val selectElaborator: SelectElaborator =
        SelectElaborator:
          case (tpe, field, _) if rootTypes.contains(tpe) =>
            Elab.failure(s"Field '$field' requires authentication.")
