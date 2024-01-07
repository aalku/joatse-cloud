package org.aalku.joatse.cloud.service.sharing.http;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.ref.WeakReference;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.aalku.joatse.cloud.config.ListenerConfigurationDetector;
import org.aalku.joatse.cloud.service.sharing.SharingManager;
import org.aalku.joatse.cloud.service.sharing.shared.SharedResourceLot;
import org.aalku.joatse.cloud.tools.io.AsyncTcpPortListener;
import org.aalku.joatse.cloud.tools.io.IOTools;
import org.aalku.joatse.cloud.tools.io.PortRange;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.client.Origin.Address;
import org.eclipse.jetty.client.Origin.Protocol;
import org.eclipse.jetty.client.ProxyConfiguration.Proxy;
import org.eclipse.jetty.client.SwitchboardConnection;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.proxy.AbstractProxyServlet;
import org.eclipse.jetty.proxy.AfterContentTransformer;
import org.eclipse.jetty.proxy.AsyncMiddleManServlet;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketListener;
import org.eclipse.jetty.websocket.api.exceptions.CloseException;
import org.eclipse.jetty.websocket.api.exceptions.UpgradeException;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.core.server.WebSocketServerComponents;
import org.eclipse.jetty.websocket.server.JettyWebSocketServerContainer;
import org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class HttpProxyManager implements InitializingBean, DisposableBean {

	private CopyOnWriteArrayList<WeakReference<WebSocketClient>> wsClients = new CopyOnWriteArrayList<>();
	private ScheduledExecutorService scheduler;
	
	public class ServerSideWsHandler implements WebSocketListener {
		private CompletableFuture<Session> cfOnConnect = new CompletableFuture<>();
		private CompletableFuture<Void> cfOnClose = new CompletableFuture<>();
		private Consumer<String> textMessageHandler;
		private Consumer<byte[]> binaryMessageHandler;
		@Override
		public void onWebSocketConnect(Session session) {
			log.info(String.format("S onWebSocketConnect: %s", session.getUpgradeRequest().getRequestURI()));
			cfOnConnect.complete(session);
		}
		@Override
		public void onWebSocketClose(int statusCode, String reason) {
			log.info(String.format("S onWebSocketClose: %s, %s", statusCode, reason));
			cfOnClose.complete(null);
		}
		@Override
		public void onWebSocketError(Throwable cause) {
			log.info(String.format("S onWebSocketError: %s", cause));
			cfOnConnect.completeExceptionally(cause);
		}
		@Override
		public void onWebSocketText(String message) {
			log.info(String.format("S onWebSocketText: %s", message));
			textMessageHandler.accept(message);
		}
		@Override
		public void onWebSocketBinary(byte[] payload, int offset, int len) {
			log.info(String.format("S onWebSocketBinary"));
			byte[] m = new byte[len];
			System.arraycopy(payload, offset, m, 0, len);
			binaryMessageHandler.accept(m);
		}
		public void setTextMessageHandler(Consumer<String> textMessageHandler) {
			this.textMessageHandler = textMessageHandler;
		}
		public void setBinaryMessageHandler(Consumer<byte[]> binaryMessageHandler) {
			this.binaryMessageHandler = binaryMessageHandler;
		}
		public void setCloseHandler(Runnable task) {
			cfOnClose.thenRun(task);
		}
		public CompletableFuture<Session> getSession() {
			return cfOnConnect;
		}
	}
	
	public class ClientSideWsHandler implements WebSocketListener {
		private CompletableFuture<Session> cfOnConnect = new CompletableFuture<>();
		private CompletableFuture<Void> cfOnClose = new CompletableFuture<>();
		private Consumer<String> textMessageHandler;
		private Consumer<byte[]> binaryMessageHandler;
		private CompletableFuture<Integer> cfErrorCode = new CompletableFuture<>();
		public ClientSideWsHandler() {
		}
		@Override
		public void onWebSocketConnect(Session session) {
			log.info(String.format("C onWebSocketConnect: %s", session.getUpgradeRequest().getRequestURI()));
			cfOnConnect.complete(session);
		}
		@Override
		public void onWebSocketClose(int statusCode, String reason) {
			log.info(String.format("C onWebSocketClose: %s, %s", statusCode, reason));
			cfOnClose.complete(null);
		}
		@Override
		public void onWebSocketError(Throwable cause) {
			log.info(String.format("C onWebSocketError: %s", cause));
			
			if (cause instanceof UpgradeException) {
				cfErrorCode.complete(((UpgradeException) cause).getResponseStatusCode());
			} else if (cause instanceof CloseException) {
				cfErrorCode.complete(((CloseException) cause).getStatusCode());
			}
			cfOnConnect.completeExceptionally(cause);
		}
		@Override
		public void onWebSocketText(String message) {
			log.info(String.format("C onWebSocketText: %s", message));
			textMessageHandler.accept(message);
		}
		@Override
		public void onWebSocketBinary(byte[] payload, int offset, int len) {
			log.info(String.format("C onWebSocketBinary"));
			byte[] m = new byte[len];
			System.arraycopy(payload, offset, m, 0, len);
			binaryMessageHandler.accept(m);
		}
		public void setTextMessageHandler(Consumer<String> textMessageHandler) {
			this.textMessageHandler = textMessageHandler;
		}
		public void setBinaryMessageHandler(Consumer<byte[]> binaryMessageHandler) {
			this.binaryMessageHandler = binaryMessageHandler;
		}
		public void setCloseHandler(Runnable task) {
			cfOnClose.thenRun(task);
		}
		public CompletableFuture<Session> getSession() {
			return cfOnConnect;
		}
		public Future<Integer> getErrorCode() {
			return cfErrorCode;
		}
	}


	private static final String COOKIE_JOATSE_HTTP_TUNNEL_TARGET_ID = "JoatseHttpTunnelTargetId";

	private static final String REQUEST_KEY_HTTPTUNNEL = "httpTunnel";
	private static final String REQUEST_KEY_REWRITE_HEADERS = "rewriteHeaders";
	private static final String REQUEST_KEY_HIDE_PROXY = "hideProxy";
	private static final String REQUEST_KEY_JOATSE_CLEAR_COOKIES = "JOATSE_CLEAR_COOKIES";
	
	private static final String REQUEST_PROXY_HEADER_HTTPTUNNEL = "joatse-header-httptunnel-" + System.currentTimeMillis();

	protected static final Set<String> REVERSE_PROXY_HEADERS = new LinkedHashSet<>(
			Arrays.asList("Location", "Content-Location", "URI"));

	private static final Pattern PATTERN_URL_PREFFIX = Pattern.compile("(?<!\\w)http(s?)://[-\\w_.]+(:[0-9]+)?(?![-\\w_.])");
	private static final Pattern PATTERN_CONTENT_TYPE_TEXT = Pattern.compile("^(text/.*|application/manifest[+]json.*|application/json.*|application/javascript.*)$");
	
	public static class UrlRewriteConfig {
		Map<String, String> urlRewriteMap = Collections.synchronizedMap(new LinkedHashMap<>());
		Map<String, String> urlReverseRewriteMap = Collections.synchronizedMap(new LinkedHashMap<>());
	}
	
	private final class AsyncMiddleManServletExtension extends AsyncMiddleManServlet {
		private static final long serialVersionUID = 1L;
		private boolean unsafeHttpClient;
//		private WebSocketComponents wsComponents;
//		private WebSocketMappings wsMapping;

		public AsyncMiddleManServletExtension(boolean unsafeHttpClient) {
			this.unsafeHttpClient = unsafeHttpClient;
		}
		
		@Override
		public void init() throws ServletException {
			super.init();
            ServletContext servletContext = getServletContext();
//            wsComponents = 
            WebSocketServerComponents.getWebSocketComponents(servletContext);
//            wsMapping = new WebSocketMappings(wsComponents);
		}

		@Override
		protected Logger createLogger() {
			return logJetty;
		}

		@Override
		protected HttpClient newHttpClient(ClientConnector clientConnector) {
	        HttpClient client = new HttpClient(new HttpClientTransportOverHTTP(clientConnector) {
	        	@Override
	        	public Origin newOrigin(HttpRequest request) {
	        		request.headers(h->{
	        			String ht = h.get(REQUEST_PROXY_HEADER_HTTPTUNNEL);
	        			if (ht != null) {
	        				h.remove(REQUEST_PROXY_HEADER_HTTPTUNNEL);
	        				if (request.getTag() == null) {
	        					HttpTunnel tunnel = tunnelFromHeader(ht);
	        					request.tag(tunnel);
	        				}
	        			}
	        		});
	        		Origin origin = super.newOrigin(request);
					return origin;
	        	}
	        });
			client.getSslContextFactory().setTrustAll(unsafeHttpClient);
			client.getProxyConfiguration().addProxy(new JoatseProxy(
							new Origin.Address("localhost", switchboardPortListener.getAddress().getPort()), false,
							null, null));
			return client;
		}

		private HttpTunnel tunnelFromHeader(String headerValue) {
			String[] a = headerValue.split(":");
			UUID uuid = UUID.fromString(a[0]);
			long id = Long.parseLong(a[1], 16);
			HttpTunnel httpTunnelById = sharingManager.getHttpTunnelById(uuid, id);
			return httpTunnelById;
		}
		
		@Override
		public void service(ServletRequest request, ServletResponse response) throws ServletException, IOException {
			try {
				HttpServletRequest servletRequest = (HttpServletRequest) request;
				HttpServletResponse servletResponse = (HttpServletResponse) response;

		        // ID http tunnel
				InetAddress remoteAddress = InetAddress.getByName(request.getRemoteAddr());
				int serverPort = request.getServerPort();
				String serverName = request.getServerName();
				String scheme = request.getScheme();
				HttpTunnel httpTunnel = sharingManager.getTunnelForHttpRequest(remoteAddress, serverPort, serverName, scheme);

				if (Optional.ofNullable(servletRequest.getHeader("Upgrade")).filter(x->x.equals("websocket")).isPresent()) {
			        websocketUpgrade(servletRequest, servletResponse, httpTunnel);
					return;
				}
				
				if (httpTunnel != null) {
					log.info("Request {} {} for tunnel {}", servletRequest.getMethod(),
							servletRequest.getRequestURL(), httpTunnel.getTargetId());
					request.setAttribute(REQUEST_KEY_HTTPTUNNEL, httpTunnel);
					request.setAttribute(REQUEST_KEY_REWRITE_HEADERS, true); // TODO
					request.setAttribute(REQUEST_KEY_HIDE_PROXY, httpTunnel.isHideProxy());
					super.service(request, response);
				} else {
					log.warn("Request {} {} rejected: Unknown tunnel or unauthorized address",
							servletRequest.getMethod(), servletRequest.getRequestURL());
					HttpServletResponse hsr = (HttpServletResponse) response;
					try (PrintWriter pw = new PrintWriter(hsr.getOutputStream())) {
						hsr.setStatus(404);
						
						StringBuilder loginUrl = getPublicCloudUrl();
						String requestUrl = getRequestUrl(servletRequest);
						List<MediaType> accepted = MediaType.parseMediaTypes(servletRequest.getHeader("Accept"));						
						if (accepted.contains(MediaType.TEXT_HTML)) {
							hsr.setContentType("text/html;charset=UTF-8");
							pw.println("<html><head><title>511 Network Authentication Required</title>");
							pw.println("<script>");
							pw.println("</script>");
							pw.println("</head><body>");
							pw.print("<h1>511 Network Authentication Required</h1>");
							
							pw.print("<p>Can't find tunnel matching <span id='requested-url'>" + HtmlUtils.htmlEscape(requestUrl)
									+ "</span> allowed to be used from your IP address "
									+ HtmlUtils.htmlEscape(remoteAddress.getHostAddress()) + "</p>");
							
							pw.print("<p>You might want to log in at <a href='"
									+ HtmlUtils.htmlEscape(new StringBuilder(loginUrl).append("?triedToAccessHttp=" + URLEncoder.encode(requestUrl, "utf-8")).toString()) + "'>"
									+ HtmlUtils.htmlEscape(loginUrl.toString()) + "</a></p>");
							
							pw.print("</body></html>");
						} else {
							hsr.sendError(511);
						}
					}
					// throw new RuntimeException("Can't find tunnel matching");
				}
			} catch (Exception e) {
				log.error("Error " + request, e);
				throw new RuntimeException(e);
			}
		}

		private void websocketUpgrade(HttpServletRequest servletRequest,
				HttpServletResponse servletResponse, HttpTunnel httpTunnel)
				throws UnknownHostException, IOException, URISyntaxException, MalformedURLException, ServletException,
				InterruptedException, ExecutionException {

			if (httpTunnel == null) {
				servletResponse.sendError(404);
				return;
			}
			
			ServletContext servletContext = servletRequest.getServletContext();
			JettyWebSocketServerContainer container = JettyWebSocketServerContainer
					.getContainer(servletContext);
			
			URI mappedUri = setWebSocketScheme(new URI(rewriteUrl(new URL(servletRequest.getRequestURL().toString()), httpTunnel)));

			log.debug(String.format("WebSockets proxy connection to %s", mappedUri));
			ClientUpgradeRequest clientUpgradeRequest = newProxyClientUpgradeRequest(servletRequest, httpTunnel);
			WebSocketClient client = newWsClient();
			
			ServerSideWsHandler serverSideWsHandler = new ServerSideWsHandler();
			ClientSideWsHandler clientSideHandler = new ClientSideWsHandler();
			serverSideWsHandler.setCloseHandler(()->IOTools.runFailable(()->client.stop()));
			clientSideHandler.getSession().thenAccept(s->{
				serverSideWsHandler.setTextMessageHandler(m->{
					try {
						s.getRemote().sendString(m);
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				});
				// TODO
			});
			serverSideWsHandler.getSession().thenAccept(s->{
				clientSideHandler.setTextMessageHandler(m->{
					try {
						s.getRemote().sendString(m);
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				});
				// TODO
			});
			
			
			CompletableFuture<Session> futureConnect = client.connect(clientSideHandler, mappedUri, clientUpgradeRequest);
			boolean clientConnected = futureConnect.handle((s,e)->{
					if (e != null) {
						IOTools.runFailable(()->client.stop());
						return false;
					} else {
						clientSideHandler.setCloseHandler(()->IOTools.runFailable(()->client.stop()));
						return true;
					}
			}).get();
			if (clientConnected) {
				boolean ok = container.upgrade((upgradeRequest, upgradeResponse) -> {
					return serverSideWsHandler;
				}, servletRequest, servletResponse);
				if (!ok) {
					IOTools.runFailable(()->client.stop());
					throw new IOException("Unable to upgrade WebSocket connection");
				}
			} else {
				int errorCode;
				try {
					errorCode = clientSideHandler.getErrorCode().get(2, TimeUnit.SECONDS);
				} catch (TimeoutException x) {
					log.error("Error waiting for an error event on a failed WS connection");
					errorCode = 500;
				}
				log.warn("Coudn't connect WS to server so we replicate the error code to the client: {}", errorCode);
				servletResponse.sendError(errorCode);
				return;
			}
		}

		private WebSocketClient newWsClient() throws ServletException {
			WebSocketClient client = new WebSocketClient(newHttpClient());
			wsClients.add(new WeakReference<WebSocketClient>(client));
			try {
				client.setStopAtShutdown(false); // <- Prevents GC
				client.setConnectTimeout(10000);
				client.setStopTimeout(5000);
				client.setMaxBinaryMessageSize(1024*1024);
				client.setMaxTextMessageSize(1024*1024);
				client.setMaxFrameSize(1024*1024);
				client.start();
			} catch (Exception e) {
				throw new ServletException("Error starting WebSocket client", e);
			}
			return client;
		}

		private URI setWebSocketScheme(URI mappedUriA) throws URISyntaxException {
			boolean isSecure = mappedUriA.getScheme().equals("https");
			String schemeB = isSecure ? "wss" : "ws";
			URI mappedUriB = new URI(schemeB,
					mappedUriA.getSchemeSpecificPart(),
					mappedUriA.getFragment());
			return mappedUriB;
		}

		private StringBuilder getPublicCloudUrl() {
			StringBuilder loginUrlSB = new StringBuilder();
			if (webListenerConfigurationDetector.getSslRequired()) {
				loginUrlSB.append("https://");
			} else {
				loginUrlSB.append("http://");
			}						
			loginUrlSB.append(webListenerConfigurationDetector.getPublicHostname());
			loginUrlSB.append(":").append(webListenerConfigurationDetector.getServerPort());
			loginUrlSB.append("/");
			return loginUrlSB;
		}

		@Override
		protected void addProxyHeaders(HttpServletRequest clientRequest, Request proxyRequest) {
			boolean hideProxy = (boolean)clientRequest.getAttribute(REQUEST_KEY_HIDE_PROXY);
			if (!hideProxy) {
				super.addProxyHeaders(clientRequest, proxyRequest);
			}
		}

		@Override
		protected HttpRequest newProxyRequest(HttpServletRequest request, String rewrittenTarget) {
			HttpRequest proxyRequest = (HttpRequest) super.newProxyRequest(request, rewrittenTarget);
			HttpTunnel hTunnel = (HttpTunnel) request.getAttribute(REQUEST_KEY_HTTPTUNNEL);
			if (hTunnel == null) {
				log.warn("request attribute REQUEST_KEY_HTTPTUNNEL is null");
			}
			if (hTunnel != null) {
				proxyRequest.tag(hTunnel);
			}
			return proxyRequest;
		}
		
		protected ClientUpgradeRequest newProxyClientUpgradeRequest(HttpServletRequest servletRequest, HttpTunnel hTunnel) {
			ClientUpgradeRequest res = new ClientUpgradeRequest();
			Map<String, List<String>> reqHeaders = Collections.list(servletRequest.getHeaderNames()).stream()
					.collect(Collectors.toMap(k -> k, k -> Collections.list(servletRequest.getHeaders(k))));
			// rewrite urls in headers
			/*
			 * Same algorithm as copyRequestHeaders, more or less
			 */
			reqHeaders.remove("Origin"); // Compatibility
			reqHeaders.remove("Host"); // Compatibility
			reqHeaders.keySet().removeIf(h->h.startsWith("Sec-WebSocket-")); // Compatibility
			for (String fieldName: new ArrayList<>(reqHeaders.keySet())) {
				boolean change = false;
				List<String> oldList = reqHeaders.get(fieldName);
				List<String> newList = new ArrayList<>(oldList.size());
				for (String field: oldList) {
					StringWriter out = new StringWriter();
					CharBuffer buffer = CharBuffer.allocate(field.length());
					buffer.put(field);
					boolean match = IOTools.rewriteStringContent(buffer, new PrintWriter(out), true, PATTERN_URL_PREFFIX, hTunnel.getUrlReverseRewriteFunction());
					change = change || match;
					newList.add(out.toString());
				}
				if (change) { // replace all headers with that name if any change
					log.debug("Changing header {} from {} to {}", fieldName, reqHeaders.get(fieldName), newList);
					reqHeaders.put(fieldName, newList);
				}
			}
			// Set them
			log.debug("WS Headers = {}", reqHeaders);
			res.setHeaders(reqHeaders);
			// Trick so that the proxy ahead know where to send this request to
			res.setHeader(REQUEST_PROXY_HEADER_HTTPTUNNEL,
					hTunnel.getTunnel().getUuid() + ":" + Long.toHexString(hTunnel.getTargetId()));
			return res;
		}


		@Override
		protected void copyRequestHeaders(HttpServletRequest clientRequest, Request proxyRequest) {
			HttpTunnel hTunnel = (HttpTunnel) clientRequest.getAttribute(REQUEST_KEY_HTTPTUNNEL);
			super.copyRequestHeaders(clientRequest, proxyRequest);
			if ((boolean)clientRequest.getAttribute(REQUEST_KEY_REWRITE_HEADERS)) {
				// rewrite urls in headers
				/*
				 * Same algorithm as newProxyClientUpgradeRequest, more or less
				 */
				proxyRequest.headers((HttpFields.Mutable m)->{
					Set<String> fieldNames = m.getFieldNamesCollection();
					for (String fieldName: fieldNames) {
						boolean change = false;
						List<HttpField> oldList = m.getFields(fieldName);
						List<String> newList = new ArrayList<>(oldList.size());
						for (HttpField field: oldList) {
							StringWriter out = new StringWriter();
							CharBuffer buffer = CharBuffer.allocate(field.getValue().length());
							buffer.put(field.getValue());
							boolean match = IOTools.rewriteStringContent(buffer, new PrintWriter(out), true, PATTERN_URL_PREFFIX, hTunnel.getUrlReverseRewriteFunction());
							change = change || match;
							newList.add(out.toString());
						}
						if (change) { // replace all headers with that name if any change
							m.remove(fieldName);
							m.put(fieldName, newList);
						}
					}
				});
			}
			/* Handle outgoing cookies */
			proxyRequest.headers((HttpFields.Mutable m)->{
				List<String> cookies = m.getFields(org.eclipse.jetty.http.HttpHeader.COOKIE).stream()
						.map(h -> h.getValue()).flatMap(v -> Arrays.asList(v.split("; ")).stream())
						.collect(Collectors.toList());
				String prefix = COOKIE_JOATSE_HTTP_TUNNEL_TARGET_ID + "=";
				Optional<String> targetIdCookie = cookies.stream().filter(c->c.startsWith(prefix)).map(c->c.substring(prefix.length())).findFirst();
				if (!targetIdCookie.isPresent() || !targetIdCookie.get().equals(hTunnel.getTargetId() + "")) {
					// Domain change. Clear all cookies here and delete them in the response
					List<String> cookieNames = cookies.stream().map(c->c.split("=",2)[0]).filter(c->!c.equals(COOKIE_JOATSE_HTTP_TUNNEL_TARGET_ID)).collect(Collectors.toList());
					clientRequest.setAttribute(REQUEST_KEY_JOATSE_CLEAR_COOKIES, new ArrayList<>(cookieNames));
					m.remove(org.eclipse.jetty.http.HttpHeader.COOKIE); // Don't pass any (and they will be deleted on response)
				} else if (targetIdCookie.isPresent()) {
					// Don't pass that cookie
					cookies.removeIf(c->c.startsWith(prefix));
					m.remove(org.eclipse.jetty.http.HttpHeader.COOKIE); // Remove all and write again
					if (cookies.size() > 0) {
						String allCookiesTogether = cookies.stream().collect(Collectors.joining("; "));
						m.add(new HttpField(org.eclipse.jetty.http.HttpHeader.COOKIE, allCookiesTogether));
					}
				}
			});
		}

		@Override
		protected String rewriteTarget(HttpServletRequest clientRequest) {
			if (!validateDestination(clientRequest.getServerName(), clientRequest.getServerPort()))
				return null;
			try {
				String urlString = getRequestUrl(clientRequest);
				URL clientURL = new URL(urlString);

				HttpTunnel tunnel = (HttpTunnel) clientRequest.getAttribute(REQUEST_KEY_HTTPTUNNEL);
				String targetUrl = rewriteUrl(clientURL, tunnel);
				log.info("Proxying {} to {}", clientURL, targetUrl);
				return targetUrl;
			} catch (MalformedURLException e) {
				throw new RuntimeException(e);
			}
		}

		private String rewriteUrl(URL clientURL, HttpTunnel tunnel) throws MalformedURLException {
			String targetUrl = new URL(tunnel.getTargetProtocol(), tunnel.getTargetDomain(),
					tunnel.getTargetPort(), clientURL.getFile()).toExternalForm();
			return targetUrl;
		}

		@Override
		protected void onServerResponseHeaders(HttpServletRequest clientRequest, HttpServletResponse proxyResponse,
				Response serverResponse) {
			super.onServerResponseHeaders(clientRequest, proxyResponse, serverResponse);
			HttpTunnel tunnel = (HttpTunnel) clientRequest.getAttribute(REQUEST_KEY_HTTPTUNNEL);
			if (tunnel != null) {
				@SuppressWarnings("unchecked")
				List<String> clear = (List<String>) clientRequest.getAttribute(REQUEST_KEY_JOATSE_CLEAR_COOKIES);
				if (clear != null) {
					Collection<String> currentCookies = proxyResponse.getHeaders("Set-Cookie");
					List<String> currentCookieNames = currentCookies.stream().map(c->c.split("=", 2)[0]).collect(Collectors.toList());
					/* Don't remove cookies that are returned by the server */
					clear.removeIf(c->currentCookieNames.contains(c));
					for (String name: clear) {
						Cookie c = new Cookie(name, "");
						c.setMaxAge(0);
						proxyResponse.addCookie(c);
					}
				}
				Cookie targetIdCookie = new Cookie(COOKIE_JOATSE_HTTP_TUNNEL_TARGET_ID, tunnel.getTargetId() + "");
				targetIdCookie.setPath("/");
				proxyResponse.addCookie(targetIdCookie);
			}
		}

		@Override
		protected String filterServerResponseHeader(HttpServletRequest clientRequest, Response serverResponse,
				String headerName, String headerValue) {
			HttpTunnel tunnel = (HttpTunnel) clientRequest.getAttribute(REQUEST_KEY_HTTPTUNNEL);
			boolean rewriteHeaders = Optional.ofNullable(clientRequest.getAttribute(REQUEST_KEY_REWRITE_HEADERS))
					.map(x -> (boolean) x).orElse(false);
			if (rewriteHeaders) {
				if (REVERSE_PROXY_HEADERS.contains(headerName)) {
					URI locationURI = URI.create(headerValue).normalize();
					if (locationURI.isAbsolute() && isProxiedLocation(tunnel, locationURI)) {
						StringBuilder newURI = URIUtil.newURIBuilder(clientRequest.getScheme(),
								clientRequest.getServerName(), clientRequest.getServerPort());
						String component = locationURI.getRawPath();
						if (component != null)
							newURI.append(component);
						component = locationURI.getRawQuery();
						if (component != null)
							newURI.append('?').append(component);
						component = locationURI.getRawFragment();
						if (component != null)
							newURI.append('#').append(component);
						return URI.create(newURI.toString()).normalize().toString();
					}
				} else if (headerName.equalsIgnoreCase("Set-Cookie")) {
					return rewriteCookieDomain(tunnel, headerValue);
				} else { 
					// TODO Maybe even dig deeper into header contents
				} 
			}
			return headerValue;
		}

		protected boolean isProxiedLocation(HttpTunnel tunnel, URI uri) {
			try {
				if (uri.getScheme().equals(tunnel.getTargetProtocol()) && uri.getHost().equals(tunnel.getTargetDomain()) && IOTools.getPort(uri.toURL()) == tunnel.getTargetPort()) {
					log.info("URI is proxied: {}", uri);
					return true;
				} else {
					log.info("URI not proxied: {}", uri);
					return false;
				}
			} catch (MalformedURLException e) {
				throw new RuntimeException(e);
			}
		}

		@Override
		protected ContentTransformer newClientRequestContentTransformer(HttpServletRequest clientRequest,
				Request proxyRequest) {
			log.info("newClientRequestContentTransformer()");
			return new AfterContentTransformer() {
				@Override
				public boolean transform(Source source, Sink sink) throws IOException {
					log.info("transform.request {}", proxyRequest.getURI());
					return false; // TODO
				}
			};
		}

		@Override
		protected ContentTransformer newServerResponseContentTransformer(HttpServletRequest clientRequest,
				HttpServletResponse proxyResponse, Response serverResponse) {
			HttpTunnel httpTunnel = (HttpTunnel) clientRequest.getAttribute(REQUEST_KEY_HTTPTUNNEL);
			SharedResourceLot tunnel = httpTunnel.getTunnel();
			URL clientRequestUrl = IOTools.runUnchecked(()->new URL(serverResponse.getRequest().getURI().toString()));
			URL proxyRequestUrl = IOTools.runUnchecked(()->new URL(clientRequest.getRequestURL().toString()));

			return new AfterContentTransformer() {
				@Override
				public boolean transform(Source source, Sink sink) throws IOException {
					String contentType = proxyResponse.getContentType();
					String contentEncoding = Optional.ofNullable(proxyResponse.getHeader("Content-Encoding")).orElse("identity");
					if (Arrays.asList("identity", "gzip").contains(contentEncoding) && contentType != null
							&& PATTERN_CONTENT_TYPE_TEXT.matcher(contentType).matches()) {
						log.info("transform.response {}: {}-->{} {} {}", 
								tunnel.getUuid(),
								proxyRequestUrl,
								clientRequestUrl,
								contentType,
								contentEncoding);
						Charset charset = Charset.forName(proxyResponse.getCharacterEncoding());

						InputStream ins = wrap(contentEncoding, source.getInputStream());
						OutputStream outs = wrap(contentEncoding, sink.getOutputStream());
						BufferedReader in = new BufferedReader(new InputStreamReader(ins, charset));
						PrintWriter out = new PrintWriter(new OutputStreamWriter(new BufferedOutputStream(outs), charset));
						CharBuffer buffer = CharBuffer.allocate(1024 * 100);
						while (true) {
							int r = in.read(buffer);
							if (r < 0) {
								IOTools.rewriteStringContent(buffer, out, true, PATTERN_URL_PREFFIX, httpTunnel.getUrlRewriteFunction());
								in.close();
								out.close();
								return true;
							} else {
								if (r > 0 && buffer.hasRemaining()) {
									continue; // Buffer must be full if not last
								}
								IOTools.rewriteStringContent(buffer, out, false, PATTERN_URL_PREFFIX, httpTunnel.getUrlRewriteFunction());
							}
						}
					} else {
						log.info("transform.response can't rewrite {}-->{} {} {}", 
								proxyRequestUrl,
								clientRequestUrl,
								contentType,
								contentEncoding);
						return false;
					}
				}
				private OutputStream wrap(String contentEncoding, OutputStream out) throws IOException {
					if (contentEncoding.equals("gzip")) {
						return new GZIPOutputStream(out);
					}
					return out;
				}
				private InputStream wrap(String contentEncoding, InputStream in) throws IOException {
					if (contentEncoding.equals("gzip")) {
						return new GZIPInputStream(in);
					}
					return in;
				}
			};
		}
	}

	private static final class JoatseProxy extends Proxy {

		private JoatseProxy(Address address, boolean secure, SslContextFactory.Client sslContextFactory,
				Protocol protocol) {
			super(address, secure, sslContextFactory, protocol);
		}

		@Override
		public boolean matches(Origin origin) {
			return true;
		}

		@Override
		public ClientConnectionFactory newClientConnectionFactory(ClientConnectionFactory connectionFactory) {
			return new ClientConnectionFactory() {
				@Override
				public Connection newConnection(EndPoint endPoint, Map<String, Object> context) throws IOException {
					@SuppressWarnings("unchecked")
					Promise<Connection> promise = (Promise<Connection>) context
							.get(HttpClientTransport.HTTP_CONNECTION_PROMISE_CONTEXT_KEY);
					HttpDestination destination = (HttpDestination) context
							.get(HttpClientTransport.HTTP_DESTINATION_CONTEXT_KEY);
					log.debug("newConnection. EndPoint={}; Context=\r\n{}; Destination=\r\n{}", endPoint,
							mapToString(context), destination);
					Executor executor = destination.getHttpClient().getExecutor();
					SwitchboardConnection connection = new SwitchboardConnection(endPoint, executor, destination,
							promise, connectionFactory, context);
					return customize(connection, context);
				}
			};
		}
	}

	static Logger log = LoggerFactory.getLogger(HttpProxyManager.class);
	static Logger logJetty = LoggerFactory.getLogger(org.eclipse.jetty.proxy.AsyncProxyServlet.class);

	@Autowired
	@Qualifier("httpPortRange")
	private PortRange httpPortRange;

	@Autowired
	@Qualifier("httpUnsafePortRange")
	private PortRange httpUnsafePortRange;

	@Value("${server.ssl.key-store-type:PKCS12}")
	private String keyStoreType;

	@Value("${server.ssl.key-store:}")
	private String keyStorePath;

	@Value("${server.ssl.key-store-password:}")
	private String keyStorePassword;

	@Autowired
	private ListenerConfigurationDetector webListenerConfigurationDetector;

	@Autowired
	@Qualifier("switchboardPortListener")
	private AsyncTcpPortListener<Void> switchboardPortListener;

	@Autowired
	public SharingManager sharingManager;

	private Server unsafeClientProxyServer;
	private Server normalProxyServer;

	@Override
	public void destroy() throws Exception {
		scheduler.shutdownNow();
		if (normalProxyServer != null) {
			normalProxyServer.stop();
		}
		if (unsafeClientProxyServer != null) {
			unsafeClientProxyServer.stop();
		}
	}

	public InetAddress getRemoteInetAddress(HttpServletRequest clientRequest) {
		try {
			return InetAddress.getByName(clientRequest.getRemoteAddr());
		} catch (UnknownHostException e) {
			throw new RuntimeException(); // Impossible
		}
	}

	public String rewriteCookieDomain(HttpTunnel tunnel, String headerValue) {
		/*
		 * We actually can't rewrite domains, just remove them and hope it doesn't mess
		 * up things. This is because we don't handle subdomain hierarchy logic.
		 * 
		 * We can't edit cookies on a HttpServletResponse so we'll have to remove them
		 * all their headers and set them again.
		 * 
		 * We can't be sure this will work on different jetty versions.
		 */
		Pattern r = Pattern.compile("[;]?\\s*[Dd][Oo][Mm][Aa][Ii][Nn]=[^\\s;]*");
		Matcher m = r.matcher(headerValue);
		StringBuilder out = new StringBuilder();
		while (m.find()) {
			m.appendReplacement(out, "");
		}
		m.appendTail(out);
		String res = out.toString();
		log.debug("Replaced cookie \"{}\" by \"{}\"", headerValue, res);
		return res;
	}

	@Override
	public void afterPropertiesSet() throws Exception {
		
        scheduler = Executors.newScheduledThreadPool(2);
        scheduler.scheduleWithFixedDelay(()->{
        	if (log.isDebugEnabled()) {
	        	StringBuilder sb = new StringBuilder();
	        	String lf = String.format("%n\t");
	        	for (WeakReference<WebSocketClient> c: wsClients) {
					sb.append(Optional.ofNullable(c.get()).map(x -> x.getState()).orElse("-")).append(lf);
	        	}
	        	if (sb.length() > 0) {
	        		log.debug("WebSocketClients: {}{}", lf, sb);
	        	}
        	}
        	wsClients.removeIf(r->r.get() == null);
        }, 5, 20, TimeUnit.SECONDS);
		
		this.normalProxyServer = buildProxyServer(httpPortRange, webListenerConfigurationDetector.isSslEnabled(), false);
		this.unsafeClientProxyServer = buildProxyServer(httpUnsafePortRange, webListenerConfigurationDetector.isSslEnabled(), true);
		this.normalProxyServer.start();
		this.unsafeClientProxyServer.start();
	}

	private Server buildProxyServer(PortRange portRange, boolean ssl, boolean unsafeHttpClient) {
		Server proxy = new Server();

		if (portRange.isActive()) {
			for (int port = portRange.min(); port <= portRange.max(); port++) {
				if (ssl) {
					SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
					sslContextFactory.setKeyStorePath(keyStorePath);
					sslContextFactory.setKeyStoreType(keyStoreType);
					sslContextFactory.setKeyStorePassword(keyStorePassword);
					boolean disableSniHostCheck = true;
					proxy.addConnector(httpsConnector(port, proxy, sslContextFactory, disableSniHostCheck));

				} else {
					proxy.addConnector(httpConnector(port, proxy));
				}
			}
		}

		proxy.setHandler(servletContextHandler(new AsyncMiddleManServletExtension(unsafeHttpClient)));
		return proxy;
	}

	private static ServerConnector httpConnector(int port, Server proxy) {
		final ServerConnector connector = new ServerConnector(proxy,
				new HttpConnectionFactory(new HttpConfiguration()));
		connector.setPort(port);
		return connector;
	}

	private static ServerConnector httpsConnector(int port, Server proxy, SslContextFactory.Server sslContextFactory,
			boolean disableSniHostCheck) {
		HttpConfiguration config = new HttpConfiguration();
		SecureRequestCustomizer src = new SecureRequestCustomizer();
		src.setSniHostCheck(!disableSniHostCheck);
		config.addCustomizer(src);
		final ServerConnector sslConnector = new ServerConnector(proxy,
				new SslConnectionFactory(sslContextFactory, HttpVersion.HTTP_1_1.toString()),
				new HttpConnectionFactory(config));
		sslConnector.setPort(port);
		return sslConnector;
	}

	private static ServletHolder proxyHolder(AbstractProxyServlet proxyServlet) {
		final ServletHolder proxyHolder = new ServletHolder(proxyServlet);
		proxyHolder.setInitOrder(1);
		return proxyHolder;
	}

//	private static class MyWebSocketListener implements WebSocketListener {
//		@Override
//		public void onWebSocketText(java.lang.String message) {
//			System.out.println(message);
//			WebSocketListener.super.onWebSocketText(message);
//		}
//		
//		@Override
//		public void onWebSocketBinary(byte[] payload, int offset, int len) {
//			System.out.println("binaryMessage");
//			WebSocketListener.super.onWebSocketBinary(payload, offset, len);
//		}
//	}
	
	private static ServletContextHandler servletContextHandler(AbstractProxyServlet servlet) {
		final ServletContextHandler servletContextHandler = new ServletContextHandler();
		servletContextHandler.setContextPath("/");
//	    Servlet websocketServlet = new JettyWebSocketServlet() {
//			private static final long serialVersionUID = 1L;
//			@Override protected void configure(JettyWebSocketServletFactory factory) {
//	            factory.addMapping("/", new JettyWebSocketCreator() {
//					@Override
//					public Object createWebSocket(JettyServerUpgradeRequest req, JettyServerUpgradeResponse resp) {
//						return new MyWebSocketListener();
//					}
//				});
//	        }
//	    };
//	    servletContextHandler.addServlet(new ServletHolder(websocketServlet), "ws://*");
		servletContextHandler.addServlet(proxyHolder(servlet), "/*");
		JettyWebSocketServletContainerInitializer.configure(servletContextHandler, null);
		return servletContextHandler;
	}

	private static String mapToString(Map<String, Object> context) {
		return context.entrySet().stream().map(e -> String.format("  %s:\t%s", e.getKey(), e.getValue()))
				.collect(Collectors.joining(",\r\n"));
	}

	private static String getRequestUrl(HttpServletRequest clientRequest) {
		StringBuffer sbuff = clientRequest.getRequestURL();
		String query = clientRequest.getQueryString();
		if (query != null)
			sbuff.append("?").append(query);
		String urlString = sbuff.toString();
		return urlString;
	}

}
