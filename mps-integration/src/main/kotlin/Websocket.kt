/*
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package org.modelix.modelcheck.mpsintegration
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.codec.http.websocketx.*
import io.netty.util.ReferenceCountUtil
import sun.nio.cs.UTF_8
import java.nio.channels.ClosedChannelException
import java.nio.charset.Charset

class WebSocketClient(private val channel: Channel) {
    fun send(message: ByteArray) {
        if (channel.isOpen) {
            channel.writeAndFlush(TextWebSocketFrame(Unpooled.wrappedBuffer(message, 0, message.size)))
        } else {
            channel.writeAndFlush(ClosedChannelException())
        }
    }
    fun send(message: String) {
        send(message.toByteArray(UTF_8.INSTANCE))
    }
}

class WebSocketChannelAdapter(val messageReceived: (TextWebSocketFrame, Channel) -> Unit, val closed: () -> Unit) :
    ChannelInboundHandlerAdapter() {
    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        when (msg) {
            !is WebSocketFrame, is PongWebSocketFrame -> ReferenceCountUtil.release(msg)
            is PingWebSocketFrame -> ctx.channel().writeAndFlush(PongWebSocketFrame(msg.content()))
            is CloseWebSocketFrame -> {
                closed()
                ctx.channel().close()
            }
            is TextWebSocketFrame -> messageReceived(msg, ctx.channel())
            else -> throw UnsupportedOperationException("${msg.javaClass.name} type not supported")
        }
    }
}