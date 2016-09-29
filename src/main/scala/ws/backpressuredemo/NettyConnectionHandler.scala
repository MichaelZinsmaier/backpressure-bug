package ws.backpressuredemo

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame
import io.netty.handler.codec.http.websocketx.ContinuationWebSocketFrame
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker

/**
  * Created by A416122 on 27.09.2016.
  */
class NettyConnectionHandler(private val handshaker: WebSocketClientHandshaker) extends SimpleChannelInboundHandler[AnyRef] {

  private var _handshake: Option[ChannelPromise] = None

  def handshake = if (_handshake.isDefined) _handshake.get else throw new IllegalStateException("Handshake future doesn't exist.")

  override def handlerAdded(ctx: ChannelHandlerContext) {
    _handshake = Some(ctx.newPromise())
  }

  override def channelActive(ctx: ChannelHandlerContext) {
    handshaker.handshake(ctx.channel())
  }

  override def channelInactive(ctx: ChannelHandlerContext) {
  }

  // Method will be renamed to messageReceived in Netty 5.0
  override def channelRead0(ctx: ChannelHandlerContext, msg: AnyRef): Unit = {
    val channel = ctx.channel()
    if (!handshaker.isHandshakeComplete) {
      handshaker.finishHandshake(channel, msg.asInstanceOf[FullHttpResponse])
      _handshake match {
        case Some(future) => future.setSuccess()
        case None => throw new IllegalStateException("Handshake future doesn't exist.")
      }
    }
    msg match {
      case response: FullHttpResponse => {
        // ignore for now
      }
      case binaryFrame: BinaryWebSocketFrame => {
        // ignore for now
      }
      case binaryFrame: ContinuationWebSocketFrame => {
        // ignore for now
      }
      case pong: PongWebSocketFrame => {
        // ignore for now
      }
      case ping: PingWebSocketFrame => {
        // ignore for now
      }
      case close: CloseWebSocketFrame => {
        // ignore for now
        channel.close()
      }
    }
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
    cause.printStackTrace()
    if (_handshake.isDefined && !_handshake.get.isDone) {
      _handshake.get.setFailure(cause)
    }
    ctx.close()
  }

}
