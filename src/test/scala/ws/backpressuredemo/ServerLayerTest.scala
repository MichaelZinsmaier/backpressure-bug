package ws.backpressuredemo

import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.Await
import scala.concurrent.Future

import akka.actor.ActorSystem
import akka.testkit.TestKit
import akka.util.ByteString
import akka.util.ByteStringBuilder

import org.scalatest.FlatSpecLike
import org.scalatest.Matchers
import org.scalatest.Suite
import org.testng.annotations.Test
import scala.concurrent.duration.DurationInt
import scala.util.Success

import akka.NotUsed
import akka.http.scaladsl.model.ws.WebSocketRequest
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ContentType
import akka.http.scaladsl.model.ContentTypes
import akka.http.scaladsl.model.HttpEntity
import akka.http.scaladsl.model.HttpMethods
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.MediaType
import akka.http.scaladsl.model.ResponseEntity
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.headers.Host
import akka.http.scaladsl.model.ws.BinaryMessage
import akka.http.scaladsl.model.ws.Message
import akka.http.scaladsl.model.ws.UpgradeToWebSocket
import akka.http.scaladsl.model.ws.WebSocketUpgradeResponse
import akka.stream.ActorMaterializer
import akka.stream.ThrottleMode
import akka.stream.scaladsl.BidiFlow
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.TLSPlacebo

import org.scalatest.concurrent.Eventually
import org.scalatest.time.Millis
import org.scalatest.time.Seconds
import org.scalatest.time.Span


class ServerLayerTest extends TestKit(ActorSystem()) with Suite with FlatSpecLike with Matchers with Eventually {

  implicit val materializer = ActorMaterializer()
  implicit val executionContext = system.dispatcher

  val wsRequest = WebSocketRequest(Uri("http://narf.de/testUri"))

  // define test data  // expected time ~83 seconds | test takes 85 seconds    BUT sending finishes much earlier
  val chunkSizeByte = 1000
  val testData = createTestData(8)
  val delay = 10.millis

  val counterMid = new AtomicInteger(0)
  val counterStart = new AtomicInteger(0)
  val counterEnd = new AtomicInteger(0)

  @Test(groups = Array("unit"))
  def testWithoutSockets(): Unit = {
    def httpServerFlow = Flow[HttpRequest].mapAsync(1)(handler)

    val wsMessageSink = Flow[Message]
      .map(_.asBinaryMessage.getStrictData)
      .map { data =>
        counterEnd.incrementAndGet()
        data
      }
      .toMat(Sink.reduce[ByteString]((a, b) => a.concat(b)))(Keep.right)

    val wsMessageSource = Source.fromIterator(() => testData.grouped(chunkSizeByte)).map { chunk =>
      BinaryMessage(chunk)
    }.map { msg =>
      counterStart.incrementAndGet()
      msg
    }

    // concat something that doesn't complete to ensure the in direction stays open
    val stickyWsMessageSource = wsMessageSource.concatMat(Source.maybe[Message])(Keep.right)

    // wire the server with the in and out source and run it
    val server = serverLayer.reversed.join(httpServerFlow)
    val (senderPromise, doneReceiving) = stickyWsMessageSource.viaMat(websocketClientLayer.join(server))(Keep.left).toMat(wsMessageSink)(Keep.both).run()







    // await sending everything
    eventually (timeout(Span(200, Seconds)), interval(Span(500, Millis))){
      counterStart.get() shouldEqual testData.grouped(chunkSizeByte).length
    }
    println("Sending finished")

    // await receiving everything
    eventually (timeout(Span(200, Seconds)), interval(Span(500, Millis))){
      counterEnd.get() shouldEqual testData.grouped(chunkSizeByte).length
    }
    println("Receiving finished")



    // close & check data
    senderPromise.success(None) // close the ws connection in one direction
    val result: ByteString = Await.result(doneReceiving, 1.seconds)

    result.length shouldEqual testData.length
    println(counterStart.get() + " " + counterMid.get() + " " + counterEnd.get())

    // println("checking content")
    // result shouldEqual testData
  }

  /* HttpResponse ~> +-------------+ ~> +------------+ ~> ByteString
   *                 | serverLayer |    | sslPlacebo |
   *  HttpRequest <~ +-------------+ <~ +------------+ <~ ByteString
   */
  val serverLayer: BidiFlow[HttpResponse, ByteString, ByteString, HttpRequest, NotUsed] =
    Http().serverLayer.atop(TLSPlacebo())

  val websocketClientLayer: BidiFlow[Message, ByteString, ByteString, Message, Future[WebSocketUpgradeResponse]] =
    Http().webSocketClientLayer(wsRequest).atop(TLSPlacebo())

  def handler(httpRequest: HttpRequest): Future[HttpResponse] = {
    httpRequest match {
      case request @ HttpRequest(HttpMethods.GET, uri: Uri, _, _, _) => upgrade(request)
      case wrong: HttpRequest => {
        Future.successful(HttpResponse(StatusCodes.MethodNotAllowed, entity = "Method not allowed."))
      }
    }
  }

  def upgrade(request: HttpRequest): Future[HttpResponse] = {
    request.header[UpgradeToWebSocket] match {
      case Some(upgrade) =>
        val flow = Flow[Message].map(msg => {
          counterMid.incrementAndGet()
          msg
        }).via(delayMessage)
        Future.successful(upgrade.handleMessages(flow))
      case None => {
        Future.successful(HttpResponse(StatusCodes.BadRequest, entity = "Not a valid websocket request."))
      }
    }
  }

  private def delayMessage = Flow[Message].throttle(1, delay, 10, ThrottleMode.shaping)

  private def createTestData(sizeInMb: Int): ByteString = {
    val mb1 = Array.fill(1024 * 1024) {
      scala.util.Random.nextInt(255).toByte
    }
    val builder = new ByteStringBuilder()
    for { i <- 1 to sizeInMb } {
      builder.putBytes(mb1)
    }
    builder.result()
  }

}
