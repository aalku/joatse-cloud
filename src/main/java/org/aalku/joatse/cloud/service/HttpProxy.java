package org.aalku.joatse.cloud.service;

import java.io.IOException;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import org.aalku.joatse.cloud.config.ListenerConfigurationDetector;
import org.aalku.joatse.cloud.service.CloudTunnelService.JoatseTunnel;
import org.aalku.joatse.cloud.service.CloudTunnelService.TunnelRequestHttpItem;
import org.aalku.joatse.cloud.tools.io.AsyncTcpPortListener;
import org.aalku.joatse.cloud.tools.io.PortRange;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.client.Origin.Address;
import org.eclipse.jetty.client.Origin.Protocol;
import org.eclipse.jetty.client.ProxyConfiguration.Proxy;
import org.eclipse.jetty.client.SwitchboardConnection;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.proxy.AsyncProxyServlet;
import org.eclipse.jetty.proxy.ProxyServlet;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.Promise;
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
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class HttpProxy implements InitializingBean, DisposableBean {

	private static final String REQUEST_KEY_HTTPTUNNEL = "httpTunnel";
	
	private static final class JoatseProxy extends Proxy {
		
		private JoatseProxy(Address address, boolean secure, SslContextFactory.Client sslContextFactory, Protocol protocol) {
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
					log.debug("newConnection. EndPoint={}; Context=\r\n{};",
							endPoint,
							mapToString(context));
					@SuppressWarnings("unchecked")
					Promise<Connection> promise = (Promise<Connection>)context.get(HttpClientTransport.HTTP_CONNECTION_PROMISE_CONTEXT_KEY);
					HttpDestination destination = (HttpDestination)context.get(HttpClientTransport.HTTP_DESTINATION_CONTEXT_KEY);
		            Executor executor = destination.getHttpClient().getExecutor();
		            SwitchboardConnection connection = new SwitchboardConnection(endPoint, executor, destination, promise, connectionFactory, context);
		            return customize(connection, context);
				}
			};
		}
	}
	
	public static class HttpTunnel {
		public HttpTunnel(JoatseTunnel tunnel, long targetId, String targetDescription, URL targetURL,
				String cloudHostname, int listenPort, String listenUrl) {
			this.tunnel = tunnel;
			this.targetId = targetId;
			this.listenPort = listenPort;
			this.cloudHostname = cloudHostname;
			this.targetDescription = targetDescription;
			this.targetURL = targetURL;
			this.listenUrl = listenUrl;
		}
		private final JoatseTunnel tunnel;

		final long targetId;
		private final int listenPort;
		private final String cloudHostname;
		
		private final String targetDescription;
		private final URL targetURL;

		private String listenUrl;
		
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
		public String getCloudHostname() {
			return cloudHostname;
		}
		public int getListenPort() {
			return listenPort;
		}
		public JoatseTunnel getTunnel() {
			return tunnel;
		}
		public String getListenUrl() {
			return listenUrl;
		}
	}
	
		
	static Logger log = LoggerFactory.getLogger(HttpProxy.class);
	
	@Autowired
	@Qualifier("httpPortRange")
	private PortRange httpPortRange;

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
	
	private Server proxyServer;

	@Override
	public void destroy() throws Exception {
		if (proxyServer != null) {
			proxyServer.stop();
		}
	}
	
	@Override
	public void afterPropertiesSet() throws Exception {
		Server proxy = new Server();
		
		if (httpPortRange.isActive()) {
			boolean ssl = webListenerConfigurationDetector.isSslEnabled();
			for (int port = httpPortRange.min(); port <= httpPortRange.max(); port++) {
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

		AsyncProxyServlet proxyServlet = new AsyncProxyServlet() {
			private static final long serialVersionUID = 1L;

			@Override
			protected Logger createLogger() {
				return log;
			}
			
			@Override
			protected HttpClient newHttpClient(ClientConnector clientConnector) {
				HttpClient client = super.newHttpClient(clientConnector);
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
						log.info("Request {} {} for tunnel {}", httpServletRequest.getMethod(), httpServletRequest.getRequestURL(), httpTunnel.getTargetId());
						request.setAttribute(REQUEST_KEY_HTTPTUNNEL, httpTunnel);
						super.service(request, response);
					} else {
						log.warn("Request {} {} rejected", httpServletRequest.getMethod(), httpServletRequest.getRequestURL());
						((HttpServletResponse)response).sendError(404, "Unknown resource requested");
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
			protected Request newProxyRequest(HttpServletRequest request, String rewrittenTarget) {
				Request proxyRequest = super.newProxyRequest(request, rewrittenTarget);
				proxyRequest.tag(request.getAttribute(REQUEST_KEY_HTTPTUNNEL));
				return proxyRequest;
			}

			@Override
			protected String rewriteTarget(HttpServletRequest clientRequest) {
				if (!validateDestination(clientRequest.getServerName(), clientRequest.getServerPort()))
					return null;
				try {
					URL clientURL = new URL(clientRequest.getRequestURL().toString());
					
					HttpTunnel tunnel = (HttpTunnel) clientRequest.getAttribute(REQUEST_KEY_HTTPTUNNEL);
					String targetUrl = new URL(tunnel.getTargetProtocol(), tunnel.getTargetDomain(), tunnel.getTargetPort(),
							clientURL.getFile())
							.toExternalForm();
					log.info("Proxying {} to {}", clientURL, targetUrl);
					return targetUrl;
				} catch (MalformedURLException e) {
					throw new RuntimeException(e);
				}
			}
		};
		ServletContextHandler servletContextHandler = servletContextHandler(proxyServlet);
		proxy.setHandler(servletContextHandler);
		proxy.start();

		this.proxyServer = proxy;
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

	private static ServletHolder proxyHolder(ProxyServlet proxyServlet) {
		final ServletHolder proxyHolder = new ServletHolder(proxyServlet);
		proxyHolder.setInitOrder(1);
		return proxyHolder;
	}

	private static ServletContextHandler servletContextHandler(ProxyServlet servlet) {
		final ServletContextHandler servletContextHandler = new ServletContextHandler();
		servletContextHandler.setContextPath("/");
		servletContextHandler.addServlet(proxyHolder(servlet), "/*");
		return servletContextHandler;
	}

	public HttpTunnel newHttpTunnel(JoatseTunnel tunnel, TunnelRequestHttpItem r, String cloudHostname,
			int listenPort) {
		String protocol = webListenerConfigurationDetector.isSslEnabled() ? "https" : "http";
		String listenUrl;
		try {
			listenUrl = new URL(protocol, cloudHostname, listenPort, r.targetUrl.getFile()).toString();
		} catch (MalformedURLException e) {
			throw new RuntimeException(e.toString(), e);
		}
		HttpTunnel res = new HttpTunnel(tunnel, r.targetId, r.targetDescription, r.targetUrl, cloudHostname, listenPort,
				listenUrl);
		return res;
	}
	
	private static String mapToString(Map<String, Object> context) {
		return context.entrySet().stream()
				.map(e -> String.format("  %s:\t%s", e.getKey(), e.getValue()))
				.collect(Collectors.joining(",\r\n"));
	}

}
