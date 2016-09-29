/* Copyrights owned by Atos and Siemens, 2015. */
package ws.backpressuredemo

import java.net.URI

import io.netty.bootstrap.Bootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelInitializer
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.http.DefaultHttpHeaders
import io.netty.handler.codec.http.HttpClientCodec
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory
import io.netty.handler.codec.http.websocketx.WebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketVersion

class NettyWebsocketClientConnection(hostname: String, port: Int, path: String, maxSizeWS: Int) {

  val uri = new URI("ws://" + hostname + ":" + port + "/" + path)
  val scheme = uri.getScheme
  val group = new NioEventLoopGroup()

  val headers = new DefaultHttpHeaders()
  val handshaker = WebSocketClientHandshakerFactory.newHandshaker(uri, WebSocketVersion.V13, null, false, headers, maxSizeWS)
  val handler = new NettyConnectionHandler(handshaker)
  val bootstrap = new Bootstrap()

  private var channel: Option[Channel] = None

  /** Connects to the server. */
  def connect(): Unit = {
    if (channel.isEmpty) {
      val initializer = new ChannelInitializer[SocketChannel]() {
        protected override def initChannel(channel: SocketChannel) {
          val pipeline = channel.pipeline()
          pipeline.addLast(new HttpClientCodec(), new HttpObjectAggregator(8192), handler)
        }
      }
      bootstrap.group(group).channel(classOf[NioSocketChannel]).handler(initializer)
      channel = Some(bootstrap.connect(hostname, port).sync().channel())
    } else {
      throw new IllegalStateException("Channel is already open.")
    }
  }

  /** Sends something over the connection. */
  def send(frame: WebSocketFrame): ChannelFuture = {
    if (channel.isDefined) {
      channel.get.writeAndFlush(frame)
    } else {
      throw new IllegalStateException("Channel is not open.")
    }
  }

  /** should be true once the IO Thread is ready to consume data => internal buffers are empty */
  def isWritable(): Boolean = {
    channel.get.isWritable
  }


  def sendCloseFrame(): ChannelFuture = {
    if (channel.isDefined) {
      channel.get.writeAndFlush(new CloseWebSocketFrame())
    } else {
      throw new IllegalStateException("Channel is not open.")
    }
  }

  /** Shuts down the client. */
  def shutdown(): Unit = {
    if (channel.isDefined) {
      channel.get.closeFuture().sync()
    }
    group.shutdownGracefully().sync()
  }

}



