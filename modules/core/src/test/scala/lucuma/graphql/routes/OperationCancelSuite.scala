// Copyright (c) 2016-2026 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.effect.IO
import clue.model.StreamingMessage.FromClient
import clue.model.StreamingMessage.FromServer

import scala.concurrent.duration.*

/**
 * The graphql-transport-ws protocol lets a client run more than one operation at a time, and a
 * client `complete` message stops the operation with that id. This holds for single result
 * operations too: the server must not send the result after the client sent `complete`. This
 * suite checks queries and mutations on the virtual clock; `slow` and `slowUpdate` produce one
 * result after 10 seconds.
 */
final class OperationCancelSuite extends ConnectionSuite:

  private def slowQuery(id: String): FromClient =
    fromClient(s"""{"id":"$id","type":"subscribe","payload":{"query":"query { slow }"}}""")

  private def slowMutation(id: String): FromClient =
    fromClient(s"""{"id":"$id","type":"subscribe","payload":{"query":"mutation { slowUpdate }"}}""")

  // The settle time gives every started operation time to finish.
  private def replies(ms: FromClient*): IO[List[Reply]] =
    repliesAfter(1.hour)(ms*)

  private def isNext(id: String)(r: Reply): Boolean = r match
    case Reply.Send(FromServer.Next(`id`, _)) => true
    case _                                    => false

  private def isPong(r: Reply): Boolean = r match
    case Reply.Send(FromServer.Pong(_)) => true
    case _                              => false

  private def isError(id: String)(r: Reply): Boolean = r match
    case Reply.Send(FromServer.Error(`id`, _)) => true
    case _                                     => false

  private def countComplete(id: String)(rs: List[Reply]): Int =
    rs.count(_ == Reply.Send(FromServer.Complete(id)))

  // Every message that carries an operation id: `next`, `error` and `complete`.
  private def hasId(id: String)(r: Reply): Boolean = r match
    case Reply.Send(FromServer.Next(`id`, _))  => true
    case Reply.Send(FromServer.Error(`id`, _)) => true
    case Reply.Send(FromServer.Complete(`id`)) => true
    case _                                     => false

  // The client stopped listening, so the server sends nothing at all for the id: no result and
  // no `complete` either.
  test("a client complete cancels a running query and sends nothing for its id"):
    replies(slowQuery("1"), complete("1")).map: obt =>
      assert(!obt.exists(hasId("1")), s"expected no message for the canceled query, got $obt")

  test("a client complete cancels a running mutation and sends nothing for its id"):
    replies(slowMutation("1"), complete("1")).map: obt =>
      assert(!obt.exists(hasId("1")), s"expected no message for the canceled mutation, got $obt")

  test("a slow query does not block messages that arrive after it"):
    replies(slowQuery("1"), ping).map: obt =>
      val pong = obt.indexWhere(isPong)
      val next = obt.indexWhere(isNext("1"))
      assert(pong >= 0 && next >= 0 && pong < next, s"expected pong before the query result, got $obt")

  test("a query that runs to the end sends its result and then complete"):
    replies(slowQuery("1")).map: obt =>
      val next = obt.indexWhere(isNext("1"))
      val comp = obt.indexOf(Reply.Send(FromServer.Complete("1")))
      assert(next >= 0 && next < comp, s"expected a result and then complete, got $obt")

  test("the id of a canceled query is free for reuse"):
    replies(slowQuery("1"), complete("1"), slowQuery("1")).map: obt =>
      assert(!obt.exists(_.isTerminal), s"the reused id closed the socket, got $obt")
      assertEquals(obt.count(isNext("1")), 1, s"expected one result for the second query, got $obt")
      // Only the second query completes. A `complete` from the cancel would end the second
      // query on the client before its result arrived.
      assertEquals(countComplete("1")(obt), 1, s"expected one complete for id 1, got $obt")

  test("a client complete that arrives after the query finished changes nothing"):
    repliesOf(1.hour)(conn =>
      conn.receive(slowQuery("1")) *> IO.sleep(1.hour) *> conn.receive(complete("1"))
    ).map: obt =>
      assertEquals(obt.count(isNext("1")), 1, s"expected one result, got $obt")
      assertEquals(countComplete("1")(obt), 1, s"expected one complete for id 1, got $obt")
      assert(!obt.exists(isError("1")), s"the late complete produced an error, got $obt")
      assert(!obt.exists(_.isTerminal), s"the late complete closed the socket, got $obt")

  test("a client complete for an id that was never used is ignored"):
    replies(complete("99")).map: obt =>
      assertEquals(countComplete("99")(obt), 0, s"expected no complete for id 99, got $obt")
      assert(!obt.exists(isError("99")), s"the unknown id produced an error, got $obt")
      assert(!obt.exists(_.isTerminal), s"the unknown id closed the socket, got $obt")

  test("a second subscribe that reuses the id of a running query closes the socket with 4409"):
    replies(slowQuery("1"), slowQuery("1")).map: obt =>
      val close = Reply.CloseWith(GraphQLWSError.SubscriberAlreadyExists("1"))
      assert(obt.contains(close), s"expected a 4409 close request, got $obt")
