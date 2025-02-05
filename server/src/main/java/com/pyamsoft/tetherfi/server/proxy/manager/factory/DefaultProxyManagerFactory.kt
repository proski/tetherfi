package com.pyamsoft.tetherfi.server.proxy.manager.factory

import androidx.annotation.CheckResult
import com.pyamsoft.tetherfi.server.ProxyDebug
import com.pyamsoft.tetherfi.server.ServerInternalApi
import com.pyamsoft.tetherfi.server.proxy.SharedProxy
import com.pyamsoft.tetherfi.server.proxy.manager.ProxyManager
import com.pyamsoft.tetherfi.server.proxy.manager.TcpProxyManager
import com.pyamsoft.tetherfi.server.proxy.session.ProxySession
import com.pyamsoft.tetherfi.server.proxy.session.tcp.TcpProxyData
import com.pyamsoft.tetherfi.server.urlfixer.UrlFixer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher

@Singleton
internal class DefaultProxyManagerFactory
@Inject
internal constructor(
    /** Need to use MutableSet instead of Set because of Java -> Kotlin fun. */
    @ServerInternalApi private val urlFixers: MutableSet<UrlFixer>,
    @ServerInternalApi private val dispatcher: CoroutineDispatcher,
    @ServerInternalApi private val tcpSession: ProxySession<TcpProxyData>,
    @ServerInternalApi private val proxyDebug: ProxyDebug,
) : ProxyManager.Factory {

  @CheckResult
  private fun createTcp(port: Int): ProxyManager {
    return TcpProxyManager(
        port = port,
        dispatcher = dispatcher,
        session = tcpSession,
        proxyDebug = proxyDebug,
    )
  }

  override fun create(type: SharedProxy.Type, port: Int): ProxyManager {
    return when (type) {
      SharedProxy.Type.TCP -> createTcp(port = port)
      SharedProxy.Type.UDP ->
          throw IllegalArgumentException("Unable to create UDP ProxyManager on port: $port")
    }
  }
}
