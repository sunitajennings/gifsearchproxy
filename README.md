# gifsearchproxy

This is a proxy server that accepts HTTP CONNECT requests from a client to create a tunnel between the client & the specified endpoint. The tunnel will only be created if the endpoint is a host:port combination found in src/main/resources/hosts.allow.

**Set up: **
The proxy's keystore file and extracted self-signed certificate is found in src/main/resources. You will need to add the certificate to your client's trust store in order to establish the HTTPS connection between client & proxy. The proxy server will not accept plaintext requests.
* sjsample.p12
* sjsample.cer

**Running the proxy**

`.\gradlew run`

This will start the proxy server with default values:
- listening on port 8443
- in quiet(ish) mode

To change either of these values, provide the appropriate argument to the gradle task. For example,

`.\gradlew run --args="port=9443 verbose"`

**Client-Proxy-Endpoint interactions:**
The following interactions should occur for successful interactions between client & proxy & endpoint:
- The client application opens a HTTPS connection to the proxy
- The client sends a CONNECT request to create a HTTP tunnel to a particular endpoint
- If the client sends any other method, the proxy will return 405 Method Not Allowe
- If the endpoint is allowed (listed in src/main/resources/hosts.allow) and the endpoint can be reached, the proxy will return a 200 Connection Established response
- Currently the only allowed endpoint is api.giphy.com (port 443, implied)
- If the endpoint is not allowed or cannot be reached, the proxy will return an error (403 Forbidden or 502 Bad Gateway, respectively)
- The client negotiates TLS through the tunneled connection all the way to the secure endpoint.

**Currently:** TLS negotation fails (inconsistently) with either a protocol_version error (see below) or a connection reset from the endpoint.

	javax.net.ssl.SSLHandshakeException: Received fatal alert: protocol_version
	        at java.base/sun.security.ssl.Alert.createSSLException(Alert.java:131)
	        at java.base/sun.security.ssl.Alert.createSSLException(Alert.java:117)
	        at java.base/sun.security.ssl.TransportContext.fatal(TransportContext.java:340)
	        at java.base/sun.security.ssl.Alert$AlertConsumer.consume(Alert.java:293)
	        at java.base/sun.security.ssl.TransportContext.dispatch(TransportContext.java:186)
	        at java.base/sun.security.ssl.SSLTransport.decode(SSLTransport.java:171)
	        at java.base/sun.security.ssl.SSLSocketImpl.decode(SSLSocketImpl.java:1359)
	        at java.base/sun.security.ssl.SSLSocketImpl.readHandshakeRecord(SSLSocketImpl.java:1268)
	        at java.base/sun.security.ssl.SSLSocketImpl.startHandshake(SSLSocketImpl.java:401)
	        at java.base/sun.security.ssl.SSLSocketImpl.startHandshake(SSLSocketImpl.java:373)
	        at com.oracle.samples.jsse.SSLSocketClientWithTunneling.doIt(SSLSocketClientWithTunneling.java:131)
	        at com.oracle.samples.jsse.SSLSocketClientWithTunneling.main(SSLSocketClientWithTunneling.java:27)

The client I am using to test is the [Oracle SSLSocketClientWithTunneling class](https://docs.oracle.com/javase/10/security/sample-code-illustrating-secure-socket-connection-client-and-server.htm#GUID-B9103D0C-3E6A-4301-B558-461E4CB23DC9__SSLSOCKETCLIENTWITHTUNNELING.JAVA-32D03DB5) , modified to connect to my localhost proxy port via a SSLSocket.

**Remaining work / next steps:**
1. Get client-endpoint TLS negotation working through the tunnel
2. Clean up sockets and other resources, especially under error conditions
3. Investigate using socket channels for non-blocking communication (would this improve anything?)
4. Analyze whether my stream handling would work for double-byte languages



