package org.aalku.joatse.cloud.service;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
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
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.aalku.joatse.cloud.config.ListenerConfigurationDetector;
import org.aalku.joatse.cloud.service.CloudTunnelService.JoatseTunnel;
import org.aalku.joatse.cloud.service.CloudTunnelService.TunnelRequestHttpItem;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class HttpProxy implements InitializingBean, DisposableBean {

	private static final String COOKIE_JOATSE_HTTP_TUNNEL_TARGET_ID = "JoatseHttpTunnelTargetId";

	private static final String REQUEST_KEY_HTTPTUNNEL = "httpTunnel";
	private static final String REQUEST_KEY_REWRITE_HEADERS = "rewriteHeaders";
	private static final String REQUEST_KEY_HIDE_PROXY = "hideProxy";
	private static final String REQUEST_KEY_JOATSE_CLEAR_COOKIES = "JOATSE_CLEAR_COOKIES";

	protected static final Set<String> REVERSE_PROXY_HEADERS = new LinkedHashSet<>(
			Arrays.asList("Location", "Content-Location", "URI"));

	private static final Pattern PATTERN_URL_PREFFIX = Pattern.compile("(?<!\\w)http(s?)://[-\\w_.]+(:[0-9]+)?(?![-\\w_.])");
	private static final Pattern PATTERN_CONTENT_TYPE_TEXT = Pattern.compile("^(text/.*|application/manifest[+]json.*|application/json.*|application/javascript.*)$");

	public static class UrlRewriteConfig {
		private Map<String, String> urlRewriteMap = Collections.synchronizedMap(new LinkedHashMap<>());
		private Map<String, String> urlReverseRewriteMap = Collections.synchronizedMap(new LinkedHashMap<>());
	}
	
	private final class AsyncMiddleManServletExtension extends AsyncMiddleManServlet {
		private static final long serialVersionUID = 1L;
		private boolean unsafeHttpClient;

		public AsyncMiddleManServletExtension(boolean unsafeHttpClient) {
			this.unsafeHttpClient = unsafeHttpClient;
		}

		@Override
		protected Logger createLogger() {
			return logJetty;
		}

		@Override
		protected HttpClient newHttpClient(ClientConnector clientConnector) {
			HttpClient client = super.newHttpClient(clientConnector);
			client.getSslContextFactory().setTrustAll(unsafeHttpClient);
			client.getProxyConfiguration().getProxies()
					.add(new JoatseProxy(
							new Origin.Address("localhost", switchboardPortListener.getAddress().getPort()), false,
							null, null));
			return client;
		}

		@Override
		public void service(ServletRequest request, ServletResponse response) throws ServletException, IOException {
			try {
				InetAddress remoteAddress = InetAddress.getByName(request.getRemoteAddr());
				HttpServletRequest httpServletRequest = (HttpServletRequest) request;
				int serverPort = request.getServerPort();
				String serverName = request.getServerName();
				List<HttpTunnel> tunnelsMatching = tunnelRegistry.findMatchingHttpTunnel(remoteAddress, serverPort,
						serverName);
				HttpTunnel httpTunnel = tunnelsMatching.size() == 1 ? tunnelsMatching.get(0) : null;
				if (httpTunnel != null) {
					log.info("Request {} {} for tunnel {}", httpServletRequest.getMethod(),
							httpServletRequest.getRequestURL(), httpTunnel.getTargetId());
					request.setAttribute(REQUEST_KEY_HTTPTUNNEL, httpTunnel);
					request.setAttribute(REQUEST_KEY_REWRITE_HEADERS, true); // TODO
					request.setAttribute(REQUEST_KEY_HIDE_PROXY, true); // TODO
					super.service(request, response);
				} else {
					log.warn("Request {} {} rejected", httpServletRequest.getMethod(),
							httpServletRequest.getRequestURL());
					((HttpServletResponse) response).sendError(404, "Unknown resource requested");
					if (tunnelsMatching.size() > 1) {
						throw new RuntimeException("Found several tunnels matching: " + tunnelsMatching.size());
					} else {
						throw new RuntimeException("Can't find tunnel matching");
					}
				}
			} catch (Exception e) {
				log.error("Error " + request, e);
				throw new RuntimeException(e);
			}
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
			if (hTunnel != null) {
				proxyRequest.tag(hTunnel);
			}
			return proxyRequest;
		}

		@Override
		protected void copyRequestHeaders(HttpServletRequest clientRequest, Request proxyRequest) {
			HttpTunnel hTunnel = (HttpTunnel) clientRequest.getAttribute(REQUEST_KEY_HTTPTUNNEL);
			super.copyRequestHeaders(clientRequest, proxyRequest);
			if ((boolean)clientRequest.getAttribute(REQUEST_KEY_REWRITE_HEADERS)) {
				// rewrite urls in headers
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
							boolean match = IOTools.rewriteStringContent(buffer, new PrintWriter(out), true, PATTERN_URL_PREFFIX, hTunnel.getUrlReverseRewriteFunction(getRemoteInetAddress(clientRequest)));
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
				URL clientURL = new URL(clientRequest.getRequestURL().toString());

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
			JoatseTunnel tunnel = httpTunnel.getTunnel();
			URL clientRequestUrl = IOTools.runUnchecked(()->new URL(serverResponse.getRequest().getURI().toString()));
			URL proxyRequestUrl = IOTools.runUnchecked(()->new URL(clientRequest.getRequestURL().toString()));

			return new AfterContentTransformer() {
				@Override
				public boolean transform(Source source, Sink sink) throws IOException {
					String contentType = proxyResponse.getContentType();
					String contentEncoding = Optional.ofNullable(proxyResponse.getHeader("Content-Encoding")).orElse("identity");
					if (Arrays.asList("identity", "gzip").contains(contentEncoding)
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
								IOTools.rewriteStringContent(buffer, out, true, PATTERN_URL_PREFFIX, httpTunnel.getUrlRewriteFunction(getRemoteInetAddress(clientRequest)));
								in.close();
								out.close();
								return true;
							} else {
								if (r > 0 && buffer.hasRemaining()) {
									continue; // Buffer must be full if not last
								}
								IOTools.rewriteStringContent(buffer, out, false, PATTERN_URL_PREFFIX, httpTunnel.getUrlRewriteFunction(getRemoteInetAddress(clientRequest)));
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
					log.debug("newConnection. EndPoint={}; Context=\r\n{};", endPoint, mapToString(context));
					@SuppressWarnings("unchecked")
					Promise<Connection> promise = (Promise<Connection>) context
							.get(HttpClientTransport.HTTP_CONNECTION_PROMISE_CONTEXT_KEY);
					HttpDestination destination = (HttpDestination) context
							.get(HttpClientTransport.HTTP_DESTINATION_CONTEXT_KEY);
					Executor executor = destination.getHttpClient().getExecutor();
					SwitchboardConnection connection = new SwitchboardConnection(endPoint, executor, destination,
							promise, connectionFactory, context);
					return customize(connection, context);
				}
			};
		}
	}

	public static class HttpTunnel {

		public HttpTunnel(JoatseTunnel tunnel, long targetId, String targetDescription, URL targetURL, boolean unsafe) {
			this.tunnel = tunnel;
			this.targetId = targetId;
			this.targetDescription = targetDescription;
			this.targetURL = targetURL;
			this.unsafe = unsafe;
		}

		private final JoatseTunnel tunnel;

		final long targetId;
		
		private Map<InetAddress, UrlRewriteConfig> urlRewriteConfigMap = new LinkedHashMap<>();

		private final Map<InetAddress, HttpProxy.ListenAddress> listenAddresses = new LinkedHashMap<>();

		private final String targetDescription;
		private final URL targetURL;

		private final boolean unsafe;


		public String getTargetDomain() {
			return targetURL.getHost();
		}

		public int getTargetPort() {
			return Optional.of(targetURL.getPort()).map(p -> p <= 0 ? targetURL.getDefaultPort() : p).get();
		}

		public URL getTargetURL() {
			return targetURL;
		}

		public String getTargetProtocol() {
			return targetURL.getProtocol();
		}

		public String getTargetDescription() {
			return targetDescription;
		}

		public long getTargetId() {
			return targetId;
		}

		public JoatseTunnel getTunnel() {
			return tunnel;
		}
		
		public synchronized Function<String, String> getUrlRewriteFunction(InetAddress addr) {
			return Optional.ofNullable(urlRewriteConfigMap.get(addr))
					.map((UrlRewriteConfig x) -> ((Function<String, String>) y -> x.urlRewriteMap.getOrDefault(y, y)))
					.orElse(y -> y);
		}

		public synchronized Function<String, String> getUrlReverseRewriteFunction(InetAddress addr) {
			return Optional.ofNullable(urlRewriteConfigMap.get(addr))
					.map((UrlRewriteConfig x) -> ((Function<String, String>) y -> x.urlReverseRewriteMap.getOrDefault(y, y)))
					.orElse(y -> y);
		}

		public synchronized boolean matches(InetAddress remoteAddress, int serverPort, String serverName) {
			return Optional.ofNullable(listenAddresses.get(remoteAddress))
					.filter(x -> x.listenPort == serverPort && x.cloudHostname.equals(serverName)).isPresent();
		}

		public synchronized boolean isUnsafe() {
			return this.unsafe;
		}

		public synchronized void registerListenAllowedAddress(InetAddress allowedAddress, String hostString, int serverPort, String serverProtocol) {
			if (listenAddresses.containsKey(allowedAddress)) {
				throw new IllegalStateException("You can allow each address only once");
			} else {
				listenAddresses.put(allowedAddress, new ListenAddress(serverPort, hostString, serverProtocol));
				
				UrlRewriteConfig config = new UrlRewriteConfig();
				urlRewriteConfigMap.put(allowedAddress, config);

				String tuu = serverProtocol + "://" + hostString + ":" + serverPort;
				
				URL tau = targetURL;
				int tPort = IOTools.getPort(tau);
				String tProtocol = tau.getProtocol();
				String tHost = tau.getHost();
				boolean tPortIsOptional = (tPort == 80 && tProtocol.equals("http")) || tPort == 443 && tProtocol.equals("https");
				String taus1 = tProtocol + "://" + tHost + ":" + tPort;
				Optional<String> taus2 = tPortIsOptional ? Optional.of(tProtocol + "://" + tHost) : Optional.empty();

				// FIXME tuu might have an optional port too

				config.urlRewriteMap.put(taus1, tuu);
				if (taus2.isPresent()) {
					config.urlRewriteMap.put(taus2.get(), tuu);
					config.urlReverseRewriteMap.put(tuu, taus2.get());
				} else {
					config.urlReverseRewriteMap.put(tuu, taus1);
				}
			}
		}

		public synchronized void unregisterListenAllowedAddress(InetAddress allowedAddress) {
			if (listenAddresses.remove(allowedAddress) != null) {
				throw new IllegalStateException("You can only remove allowed addresses");
			}
			urlRewriteConfigMap.remove(allowedAddress);
		}

		public Map<InetAddress, URL> getListenAddressess() {
			return listenAddresses.entrySet().stream()
					.collect(Collectors.toMap(e -> (InetAddress)e.getKey(), e -> e.getValue().getListenUrl(Optional.of(this.getTargetURL().getFile()))));
		}

	}

	public static class ListenAddress {
		private final int listenPort;
		private final String cloudProtocol;
		private final String cloudHostname;
		public ListenAddress(int listenPort, String cloudHostname, String cloudProtocol) {
			this.listenPort = listenPort;
			this.cloudProtocol = cloudProtocol;
			this.cloudHostname = cloudHostname;
		}
		public URL getListenUrl(Optional<String> file) {
			try {
				return new URL(cloudProtocol, cloudHostname, listenPort, file.orElse(""));
			} catch (MalformedURLException e) {
				throw new RuntimeException(e.toString(), e);
			}
		}
	}

	static Logger log = LoggerFactory.getLogger(HttpProxy.class);
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
	private TunnelRegistry tunnelRegistry;

	private Server unsafeClientProxyServer;
	private Server normalProxyServer;

	@Override
	public void destroy() throws Exception {
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

	private static ServletContextHandler servletContextHandler(AbstractProxyServlet servlet) {
		final ServletContextHandler servletContextHandler = new ServletContextHandler();
		servletContextHandler.setContextPath("/");
		servletContextHandler.addServlet(proxyHolder(servlet), "/*");
		return servletContextHandler;
	}

	public HttpTunnel newHttpTunnel(JoatseTunnel tunnel, TunnelRequestHttpItem r) {
		return new HttpTunnel(tunnel, r.targetId, r.targetDescription, r.targetUrl, r.unsafe);
	}

	private static String mapToString(Map<String, Object> context) {
		return context.entrySet().stream().map(e -> String.format("  %s:\t%s", e.getKey(), e.getValue()))
				.collect(Collectors.joining(",\r\n"));
	}
}
