package com.pyamsoft.tetherfi.server.proxy.manager

import com.pyamsoft.pydroid.core.Enforcer
import com.pyamsoft.pydroid.util.ifNotCancellation
import com.pyamsoft.tetherfi.server.ProxyDebug
import com.pyamsoft.tetherfi.server.proxy.SharedProxy
import com.pyamsoft.tetherfi.server.proxy.session.ProxySession
import com.pyamsoft.tetherfi.server.proxy.session.tcp.TcpProxyData
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.SocketAddress
import io.ktor.network.sockets.SocketBuilder
import io.ktor.network.sockets.isClosed
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class TcpProxyManager
internal constructor(
    private val session: ProxySession<TcpProxyData>,
    private val dispatcher: CoroutineDispatcher,
    port: Int,
    proxyDebug: ProxyDebug,
) :
    BaseProxyManager<ServerSocket>(
        SharedProxy.Type.TCP,
        dispatcher,
        port,
        proxyDebug,
    ) {

  private suspend fun runSession(connection: Socket) = coroutineScope {
    try {
      session.exchange(
          data =
              TcpProxyData(
                  connection = connection,
              ),
      )
    } catch (e: Throwable) {
      e.ifNotCancellation { errorLog(e) { "Error during runSession" } }
    }
  }

  override suspend fun openServer(
      builder: SocketBuilder,
      localAddress: SocketAddress
  ): ServerSocket {
    return builder
        .tcp()
        .bind(
            localAddress = localAddress,
        )
  }

  override suspend fun runServer(server: ServerSocket) = coroutineScope {
    Enforcer.assertOffMainThread()

    // In a loop, we wait for new TCP connections and then offload them to their own routine.
    while (isActive && !server.isClosed) {
      // We must close the connection in the launch{} after exchange is over
      val connection = server.accept()

      // Run this server loop off thread so we can handle multiple connections at once.
      launch(context = dispatcher) {
        try {
          runSession(connection)
        } finally {
          connection.dispose()
        }
      }
    }
  }

  override suspend fun onServerClosed() {
    // Don't dispose of the KTorDefaultPool here as others may use it
  }
}
