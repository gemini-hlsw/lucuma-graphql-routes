// Copyright (c) 2016-2026 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package lucuma.graphql.routes

import cats.effect.IO
import cats.syntax.all.*
import org.http4s.client.websocket.WSFrame
import org.http4s.headers.Authorization
import scodec.bits.ByteVector

/**
 * The graphql-transport-ws protocol reserves close code 4400 for a message that the server cannot
 * parse. This suite sends raw frames over a real socket and reads the close code.
 */
final class InvalidMessageSuite extends BaseSuite:

  def service(auth: Option[Authorization]): IO[Option[GraphQLService[IO]]] =
    GraphQLService(TestMapping).some.pure[IO]

  // The close code of the last frame, if the server closed the socket.
  private def closeCodeOf(frames: List[WSFrame]): Option[Int] =
    frames.lastOption.collect { case WSFrame.Close(code, _) => code }

  private def closeCode(frames: WSFrame*): IO[Option[Int]] =
    rawWsFrames(1)(frames*).map(closeCodeOf)

  test("a text frame that is not JSON closes the socket with code 4400"):
    closeCode(WSFrame.Text("not json")).assertEquals(4400.some)

  test("a JSON message of an unknown type closes the socket with code 4400"):
    closeCode(WSFrame.Text("""{"type":"bogus"}""")).assertEquals(4400.some)

  test("a JSON message that is not an object closes the socket with code 4400"):
    closeCode(WSFrame.Text("""[1,2,3]""")).assertEquals(4400.some)

  test("a binary frame closes the socket with code 4400"):
    closeCode(WSFrame.Binary(ByteVector(1, 2, 3))).assertEquals(4400.some)

  test("a message that arrives after the close gets no reply"):
    rawWsFrames(2)(WSFrame.Text("not json"), WSFrame.Text("""{"type":"connection_init"}"""))
      .map: obt =>
        assertEquals(obt.length, 1, s"the server replied after the close, got $obt")
        assertEquals(closeCodeOf(obt), 4400.some)

  test("a valid connection_init still gets an acknowledgement"):
    rawWsFrames(1)(WSFrame.Text("""{"type":"connection_init"}""")).map: obt =>
      assert(obt.exists { case WSFrame.Text(s, _) => s.contains("connection_ack"); case _ => false }, s"got $obt")
