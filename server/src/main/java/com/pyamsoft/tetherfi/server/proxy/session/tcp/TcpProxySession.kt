package com.pyamsoft.tetherfi.server.proxy.session.tcp

import androidx.annotation.CheckResult
import com.pyamsoft.pydroid.core.Enforcer
import com.pyamsoft.pydroid.core.requireNotNull
import com.pyamsoft.pydroid.util.ifNotCancellation
import com.pyamsoft.tetherfi.server.ProxyDebug
import com.pyamsoft.tetherfi.server.ServerInternalApi
import com.pyamsoft.tetherfi.server.event.ProxyRequest
import com.pyamsoft.tetherfi.server.proxy.SharedProxy
import com.pyamsoft.tetherfi.server.proxy.session.BaseProxySession
import com.pyamsoft.tetherfi.server.proxy.session.DestinationInfo
import com.pyamsoft.tetherfi.server.proxy.session.tagSocket
import com.pyamsoft.tetherfi.server.urlfixer.UrlFixer
import io.ktor.network.selector.ActorSelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.close
import io.ktor.utils.io.joinTo
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeFully
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Singleton
internal class TcpProxySession
@Inject
internal constructor(
    @ServerInternalApi proxyDebug: ProxyDebug,
    /** Need to use MutableSet instead of Set because of Java -> Kotlin fun. */
    @ServerInternalApi private val urlFixers: MutableSet<UrlFixer>,
    @ServerInternalApi private val dispatcher: CoroutineDispatcher,
) :
    BaseProxySession<TcpProxyData>(
        SharedProxy.Type.TCP,
        proxyDebug,
    ) {

  /**
   * Parse the first line of content which may be a connect call
   *
   * We must do this unbuffered because we only want the first line to determine which host and what
   * port we are connecting on
   *
   * If we buffer we may end up reading the whole input which can be huge, and OOM us.
   */
  @CheckResult
  private suspend fun parseRequest(input: ByteReadChannel): ProxyRequest? {
    /**
     * Read the first line as it should include enough data for us yeah ok, there is often extra
     * data sent over but its mainly for optimization and rarely required to actually make a
     * connection
     */
    val line = input.readUTF8Line()

    // No line, no go
    if (line.isNullOrBlank()) {
      warnLog { "No input read from proxy" }
      return null
    }

    try {
      // Given the line, it needs to be in an expected format or we can't do it
      val methodData = getMethodAndUrlString(line)
      if (methodData == null) {
        warnLog { "Unable to parse method and URL: $line" }
        return null
      }

      val urlData = getUrlAndPort(methodData.url)
      val request =
          ProxyRequest(
              url = methodData.url,
              host = urlData.hostName,
              method = methodData.method,
              port = urlData.port,
              version = methodData.version,
              raw = line,
          )
      debugLog { "Request received: $line $request" }
      return request
    } catch (e: Throwable) {
      errorLog(e) { "Unable to parse request: $line" }
      return null
    }
  }

  /**
   * Figure out the URL and the port of the connection
   *
   * If the URL does not include the port, determine it from the protocol or just assume it is HTTP
   */
  @CheckResult
  private fun getUrlAndPort(possiblyProtocolAndHostAndPort: String): DestinationInfo {
    var host: String
    var protocol: String
    var port = -1

    // This could be anything like the following
    // protocol://hostname:port
    //
    // http://example.com -> http example.com 80
    // http://example.com:69 -> http example.com 69
    // example.com:80 -> http example.com 80
    // example.com:443 -> https example.com 443
    // example.com -> http example.com 80
    // https://example.com -> https example.com 443
    // https://example.com/file.html -> https example.com 443
    // example.com:443/file.html -> https example.com 443
    // example.com/file.html -> http example.com 80
    try {
      // Just try with the normal java URI parser
      val uu = URI(possiblyProtocolAndHostAndPort)

      protocol = uu.scheme.requireNotNull()
      host = uu.host.requireNotNull()
      port = uu.port.requireNotNull()
    } catch (e: Throwable) {
      // Well that didn't work, would have hoped we didn't have to do this but

      // Do we have a port in this URL? if we do split it up
      val possiblyProtocolAndHost: String
      val portSeparator = possiblyProtocolAndHostAndPort.indexOf(':')
      if (portSeparator >= 0) {

        // Split up to just the protocol and host
        possiblyProtocolAndHost = possiblyProtocolAndHostAndPort.substring(0, portSeparator)

        // And then this, should be the port, right?
        val portString = possiblyProtocolAndHostAndPort.substring(portSeparator + 1)

        // Parse the port, or default to just 80 for HTTP traffic
        port =
            portString.toIntOrNull().let { maybePort ->
              if (maybePort == null) {
                warnLog {
                  "Port string was not a valid port: $possiblyProtocolAndHostAndPort => $portString"
                }
                // Default to port 80 for HTTP
                80
              } else {
                maybePort
              }
            }
      } else {
        // No port in the URL, this is the URL then
        possiblyProtocolAndHost = possiblyProtocolAndHostAndPort
      }

      // Then we split up the protocol
      val splitByProtocol = possiblyProtocolAndHost.split("://")

      // Strip the protocol of http:// off of the url, but if there is no protocol, we just have
      // the host name as the entire thing

      // Could be a name like mywebsite.com/filehere.html and we only want the host name
      // mywebsite.com
      val hostAndPossiblyFile = splitByProtocol[if (splitByProtocol.size == 1) 0 else 1]

      // If there is an additional file attached to this request, ignore it and just grab the URL
      host =
          hostAndPossiblyFile.indexOf("/").let { fileIndex ->
            if (fileIndex >= 0) hostAndPossiblyFile.substring(0, fileIndex) else hostAndPossiblyFile
          }

      // Guess the protocol or assume it empty
      protocol = if (splitByProtocol.size == 1) splitByProtocol[0] else ""
    }

    // If the port was passed but is some random number, guess it from the protocol
    if (port < 0) {
      // And if we don't know the protocol, good old 80
      port = if (protocol.startsWith("https")) 443 else 80
    }

    return DestinationInfo(
        // Just in-case we missed a slash, a name with a slash is not a valid hostname
        // its actually a host with a file path of ROOT, which is bad
        hostName = host.trimEnd('/'),
        port = port,
    )
  }

  /**
   * Some connection request formats are buggy, this method seeks to fix them to what it knows in
   * very specific cases is correct
   */
  @CheckResult
  private fun String.fixSpecialBuggyUrls(): String {
    var result = this
    for (fixer in urlFixers) {
      result = fixer.fix(result)
    }
    return result
  }

  /**
   * Figure out the METHOD and URL
   *
   * The METHOD is important because we need to know if this is a CONNECT call for HTTPS
   */
  @CheckResult
  private fun getMethodAndUrlString(line: String): MethodData? {
    // Line is something like
    // CONNECT https://my-internet-domain:80 HTTP/2

    // We find the first space
    val firstSpace = line.indexOf(' ')
    if (firstSpace < 0) {
      warnLog { "Invalid request line format. No space: '$line'" }
      return null
    }

    // We now have the first space, the start-of-line, and the rest-of-line
    //
    // start-of-line: CONNECT
    // rest-of-line: https://my-internet-domain:80 HTTP/2
    val restOfLine = line.substring(firstSpace + 1)

    // Need to have the last space so we know the version too
    val nextSpace = restOfLine.indexOf(' ')
    if (nextSpace < 0) {
      warnLog { "Invalid request line format. No nextSpace: '$line' => '$restOfLine'" }
      return null
    }

    // We have everything we need now
    //
    // start-of-line: CONNECT
    // middle-of-line: https://my-internet-domain
    // end-of-line: HTTP/2
    return MethodData(
        url = restOfLine.substring(0, nextSpace).trim().fixSpecialBuggyUrls(),
        method = line.substring(0, firstSpace).trim(),
        version = restOfLine.substring(nextSpace + 1).trim(),
    )
  }

  /**
   * HTTPS Connections are encrypted and so we cannot see anything further past the initial CONNECT
   * call.
   *
   * Establish the connection to a site and then continue processing the connection data
   */
  private suspend fun CoroutineScope.establishHttpsConnection(
      input: ByteReadChannel,
      output: ByteWriteChannel,
      request: ProxyRequest,
  ) {
    // We exhaust the input here because the client is sending CONNECT data to what it thinks is a
    // server but its actually us, and we don't care how they connect
    //
    // we assume the connect will work and then tell the client so they can start sending the
    // actual data
    var throwaway: String?
    do {
      throwaway = input.readUTF8Line()
    } while (isActive && !throwaway.isNullOrBlank())

    debugLog { "Establish HTTPS ACK connection: $request" }
    proxyResponse(output, "HTTP/1.1 200 Connection Established")
  }

  /**
   * Send the first communication request
   *
   * This was not an HTTPS CONNECT request, so we just pass it along to our HTTP client
   */
  private suspend fun replayHttpCommunication(
      output: ByteWriteChannel,
      request: ProxyRequest,
  ) {
    // Strip off the hostname just leaving file name for requests
    val file = request.url.replace("http://.+\\.\\w+/".toRegex(), "/")

    val newRequest = "${request.method} $file ${request.version}"

    debugLog { "Replay initial HTTP connection: $request => $newRequest" }
    output.writeFully(writeMessageAndAwaitMore(newRequest))
  }

  private suspend fun CoroutineScope.talk(input: ByteReadChannel, output: ByteWriteChannel) {
    while (isActive) {
      try {
        input.joinTo(output, closeOnEnd = false)
      } catch (_: Throwable) {
        /**
         * Exceptions when relaying traffic (like a closed socket) are not errors, it is expected
         * that this will happen as the natural end of client/host communication.
         *
         * Furthermore, even if this were an error, there is no recovery operation we can do, thus
         * it is useless to care about it.
         */
      }
    }
  }

  private suspend fun exchangeInternet(
      proxyInput: ByteReadChannel,
      proxyOutput: ByteWriteChannel,
      internetInput: ByteReadChannel,
      internetOutput: ByteWriteChannel,
      request: ProxyRequest,
  ) = coroutineScope {
    try {
      if (isHttpsConnection(request)) {
        // Establish an HTTPS connection by faking the CONNECT response
        // Send a 200 to the connecting client so that they will then continue to
        // send the actual HTTP data to the real endpoint
        establishHttpsConnection(proxyInput, proxyOutput, request)
      } else {
        // Send initial HTTP communication, since we consumed it above
        replayHttpCommunication(
            output = internetOutput,
            request = request,
        )
      }

      // Exchange data until completed
      debugLog { "Exchange rest of data: $request" }
      val job =
          launch(context = dispatcher) {
            // Send data from the internet back to the proxy in a different thread
            talk(
                input = internetInput,
                output = proxyOutput,
            )
          }

      // Send input from the proxy (clients) to the internet on this thread
      talk(
          input = proxyInput,
          output = internetOutput,
      )

      // Wait for internet communication to finish
      job.join()
      debugLog { "Done with proxy request: $request" }
    } catch (e: Throwable) {
      e.ifNotCancellation {
        errorLog(e) { "Error during Internet exchange" }
        writeError(proxyOutput)
      }
    } finally {
      proxyInput.cancel()
      proxyOutput.close()
      internetInput.cancel()
      internetOutput.close()
    }
  }

  /**
   * Given the initial proxy request, connect to the Internet from our device via the connected
   * socket
   */
  @CheckResult
  private suspend fun connectToInternet(request: ProxyRequest): Socket {
    // Tag sockets for Android O strict mode
    tagSocket()

    // We dont actually use the socket tls() method here since we are not a TLS server
    // We do the CONNECT based workaround to handle HTTPS connections

    val remote =
        InetSocketAddress(
            hostname = request.host,
            port = request.port,
        )

    val rawSocket = aSocket(ActorSelectorManager(context = dispatcher))
    return rawSocket.tcp().connect(remoteAddress = remote)
  }

  override suspend fun exchange(data: TcpProxyData) {
    Enforcer.assertOffMainThread()

    /** The Proxy is our device */
    val connection = data.connection
    val proxyInput = connection.openReadChannel()
    val proxyOutput = connection.openWriteChannel(autoFlush = true)

    /** We use a string parsing to figure out what this HTTP request wants to do */
    val request = parseRequest(proxyInput)
    try {
      if (request == null) {
        val msg = "Could not parse proxy request"
        warnLog { msg }
        writeError(proxyOutput)
        proxyInput.cancel()
        proxyOutput.close()
        return
      }

      // Given the request, connect to the Web
      connectToInternet(request).use { internet ->
        debugLog { "Proxy to: $request" }
        val internetInput = internet.openReadChannel()
        val internetOutput = internet.openWriteChannel(autoFlush = true)

        // Communicate between the web connection we've made and back to our client device
        exchangeInternet(
            proxyInput = proxyInput,
            proxyOutput = proxyOutput,
            internetInput = internetInput,
            internetOutput = internetOutput,
            request = request,
        )
      }
    } catch (e: Throwable) {
      e.ifNotCancellation {
        errorLog(e) { "Error during connect to internet: $request" }
        writeError(proxyOutput)
      }
    }
  }

  private data class MethodData(
      val url: String,
      val method: String,
      val version: String,
  )

  companion object {

    private const val LINE_ENDING = "\r\n"

    /**
     * Check if this is an HTTPS connection
     *
     * The only HTTPS connection we can read is the CONNECT call
     */
    @CheckResult
    private fun isHttpsConnection(input: ProxyRequest): Boolean {
      return input.method == "CONNECT"
    }

    /** Write a generic error back to the client socket because something has gone wrong */
    private suspend fun writeError(output: ByteWriteChannel) {
      proxyResponse(output, "HTTP/1.1 502 Bad Gateway")
    }

    /**
     * Respond to the client with a message string
     *
     * Properly line-ended with flushed output
     */
    private suspend fun proxyResponse(output: ByteWriteChannel, response: String) {
      output.apply {
        writeFully(writeMessageAndAwaitMore(response))
        writeFully(LINE_ENDING.encodeToByteArray())
        flush()
      }
    }

    /**
     * Convert a message string into a byte array
     *
     * Correctly end the line with return and newline
     */
    @CheckResult
    private fun writeMessageAndAwaitMore(message: String): ByteArray {
      val msg = if (message.endsWith(LINE_ENDING)) message else "${message}$LINE_ENDING"
      return msg.encodeToByteArray()
    }
  }
}
