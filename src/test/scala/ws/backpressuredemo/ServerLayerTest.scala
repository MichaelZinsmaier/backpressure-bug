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
  val httpHost = Host("narf.de")

  // define test data  // expected time ~83 seconds | test took 85 seconds
  val chunkSizeByte = 1000
  val testData = createTestData(8)
  val delay = 10.millis

  val counterMid = new AtomicInteger(0)
  val counterStart = new AtomicInteger(0)
  val counterEnd = new AtomicInteger(0)

  @Test(groups = Array("unit"))
  def testWithoutSockets(): Unit = {
    def httpServerFlow = Flow[HttpRequest].mapAsync(1)(handler)

    val httpDataSink = Flow[HttpResponse]
      .flatMapConcat(_.entity.dataBytes)
      .map { data =>
        counterEnd.incrementAndGet()
        data
      }
      .toMat(Sink.reduce[ByteString]((a, b) => a.concat(b)))(Keep.right)

    val httpMessageSource = Source.single {
      val data = Source.fromIterator(() => testData.grouped(chunkSizeByte)).map { msg =>
        counterStart.incrementAndGet()
        msg
      }

      val reqEntity = HttpEntity(ContentTypes.`application/octet-stream`, data).withoutSizeLimit()
      HttpRequest(method = HttpMethods.POST, entity = reqEntity)
    }

    // concat something that doesn't complete to ensure the in direction stays open
    val stickyHttpMessageSource = httpMessageSource.concatMat(Source.maybe[HttpRequest])(Keep.right)

    // wire the server with the in and out source and run it
    val server = serverLayer.reversed.join(httpServerFlow)
    val (senderPromise, doneReceiving) = stickyHttpMessageSource.viaMat(httpClientLayer.join(server))(Keep.left).toMat(httpDataSink)(Keep.both).run()







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
    senderPromise.isCompleted shouldEqual true // receiving of the response closes the connection
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

  val httpClientLayer: BidiFlow[HttpRequest, ByteString, ByteString, HttpResponse, NotUsed] =
    Http().clientLayer(httpHost).atop(TLSPlacebo())

  def handler(httpRequest: HttpRequest): Future[HttpResponse] = {
    httpRequest match {
      case request @ HttpRequest(HttpMethods.POST, uri: Uri, _, _, _) => handle(request)
      case wrong: HttpRequest => {
        Future.successful(HttpResponse(StatusCodes.MethodNotAllowed, entity = "Method not allowed."))
      }
    }
  }

  def handle(request: HttpRequest): Future[HttpResponse] = {
    // take input and throttle
    val data = request.entity.dataBytes.map(msg => {
      counterMid.incrementAndGet()
      msg
    }).via(delayMessage)

    // wrap it back
    val respEntity = HttpEntity(ContentTypes.`application/octet-stream`, data).withoutSizeLimit()
    Future.successful(HttpResponse(entity = respEntity))
  }

  private def delayMessage = Flow[ByteString].throttle(1, delay, 10, ThrottleMode.shaping)

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
