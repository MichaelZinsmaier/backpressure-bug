/* Copyrights owned by Atos and Siemens, 2015. */

package ws.backpressuredemo

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpMethods
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.ws.Message
import akka.http.scaladsl.model.ws.UpgradeToWebsocket
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Flow
import akka.util.ByteString
import akka.util.ByteStringBuilder

import io.netty.buffer.Unpooled
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelFutureListener
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame

object Main extends App {

  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()

  // define test data, test should run roughly 240 seconds (20*1024^2 / 1000 chunks * 10millis)
  // to ensure that everything is recorded
  val chunkSizeByte = 1000
  val testData = createTestData(20)
  val delay = 10.millis

  // define server address and port
  val interface = "127.0.0.1"
  val port = 9001

  // startup server
  Http().bindAndHandleAsync(handler, interface, port)

  // connect client and send data
  val nettyWebSocketClient = new NettyWebsocketClientConnection(interface, port, "", 8192)
  nettyWebSocketClient.connect()

  Thread.sleep(4000)
  val started = System.currentTimeMillis()

  testData.grouped(chunkSizeByte).foreach(data => {
      // send WS objects, final fragment flag is set
      nettyWebSocketClient.send(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(data.asByteBuffer)))
    }
  )


  while(!nettyWebSocketClient.isWritable()) {
    Thread.sleep(100)
  }

  println("channel is writable, internal buffers are empty")
  println(s"took ${((System.currentTimeMillis() - started) / 100) / 10.0} seconds")

  // enqueue close frame, shutdown once it has been send
  nettyWebSocketClient.sendCloseFrame()
  nettyWebSocketClient.shutdown()
  def handler(httpRequest: HttpRequest): Future[HttpResponse] = {
    httpRequest match {
      case request @ HttpRequest(HttpMethods.GET, uri: Uri, _, _, _) => upgrade(request)
      case wrong: HttpRequest => {
        Future.successful(HttpResponse(StatusCodes.MethodNotAllowed, entity = "Method not allowed."))
      }
    }
  }

  def upgrade(request: HttpRequest): Future[HttpResponse] = {
    request.header[UpgradeToWebsocket] match {
      case Some(upgrade) =>
        val delayingFlow = Flow[Message].map(delayMessage)
        Future.successful(upgrade.handleMessages(delayingFlow))
      case None => {
        Future.successful(HttpResponse(StatusCodes.BadRequest, entity = "Not a valid websocket request."))
      }
    }
  }

  private def delayMessage(message: Message): Message = {
    Thread.sleep(delay.toMillis)
    message
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
