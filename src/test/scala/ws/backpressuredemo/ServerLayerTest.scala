package ws.backpressuredemo

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.ws.BinaryMessage
import akka.http.scaladsl.model.ws.Message
import akka.http.scaladsl.model.ws.UpgradeToWebSocket
import akka.http.scaladsl.model.ws.WebSocketRequest
import akka.http.scaladsl.model.ws.WebSocketUpgradeResponse
import akka.stream.ActorMaterializer
import akka.stream.ThrottleMode
import akka.stream.scaladsl.BidiFlow
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.TLSPlacebo
import akka.testkit.TestKit
import akka.util.ByteString
import akka.util.ByteStringBuilder

import org.scalatest.FlatSpecLike
import org.scalatest.Matchers
import org.scalatest.Suite
import org.scalatest.concurrent.Eventually
import org.testng.annotations.Test


class ServerLayerTest extends TestKit(ActorSystem()) with Suite with FlatSpecLike with Matchers with Eventually {

  // Running with > 100.000 chunks this should not be able to complete before the initial delay elapses
  // but it does which means the source is not backpressured!

  /*
   *    Source[Messages]   ->  wsClient  ->  wsServer  ->  Delay(50seconds)  ->  Sink.ignore
   *
   *    Sink.ignore        <-  wsClient  <-  wsServer  <-  Source.maybe
   *
   */

  implicit val materializer = ActorMaterializer()
  implicit val executionContext = system.dispatcher

  val wsRequest = WebSocketRequest(Uri("http://narf.de/testUri"))

  val chunkSizeByte = 1000
  val testData = createTestData(100) // 100 Mb of data ~ 100.000 chunks


  @Test(groups = Array("unit"))
  def testWithoutSockets(): Unit = {
    def httpServerFlow = Flow[HttpRequest].mapAsync(1)(handler)

    // wrap test data in ws objects and watch for complete
    val wsMessageSource = Source.fromIterator(() => testData.grouped(chunkSizeByte))
      .map(BinaryMessage(_))
      .watchTermination()(Keep.right)

    // wire the server with the in and out source and run it
    val server = serverLayer.reversed.join(httpServerFlow)
    val senderFinished = wsMessageSource.viaMat(websocketClientLayer.join(server))(Keep.left).to(Sink.ignore).run()

    val started = System.currentTimeMillis()

    senderFinished.onSuccess {
      case _ => println(s"sending completed ${System.currentTimeMillis() - started}")
    }

    // block
    Await.ready(senderFinished, 100.seconds)
    Thread.sleep(1000)
  }


  val serverLayer: BidiFlow[HttpResponse, ByteString, ByteString, HttpRequest, NotUsed] =
    Http().serverLayer.atop(TLSPlacebo())

  val websocketClientLayer: BidiFlow[Message, ByteString, ByteString, Message, Future[WebSocketUpgradeResponse]] =
    Http().webSocketClientLayer(wsRequest).atop(TLSPlacebo())


  def handler(httpRequest: HttpRequest): Future[HttpResponse] = {
    httpRequest.header[UpgradeToWebSocket] match {
      case Some(upgrade) => {
        val src = Source.maybe[Message]
        // initial delay by 50 seconds -> create backpressure
        val sink = Flow[Message].initialDelay(50.seconds).to(Sink.ignore)

        Future.successful(upgrade.handleMessagesWithSinkSource(sink, src))
      }
      case _ => throw new Exception()
    }
  }


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
