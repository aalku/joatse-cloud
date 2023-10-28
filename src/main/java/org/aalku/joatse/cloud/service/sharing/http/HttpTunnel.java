package org.aalku.joatse.cloud.service.sharing.http;

import java.net.URL;
import java.util.Optional;
import java.util.function.Function;

import org.aalku.joatse.cloud.service.sharing.http.HttpProxyManager.UrlRewriteConfig;
import org.aalku.joatse.cloud.service.sharing.shared.SharedResourceLot;
import org.aalku.joatse.cloud.tools.io.IOTools;

public class HttpTunnel {

	public HttpTunnel(SharedResourceLot tunnel, long targetId, String targetDescription, URL targetURL, boolean unsafe, String listenHostname) {
		this.tunnel = tunnel;
		this.targetId = targetId;
		this.targetDescription = targetDescription;
		this.targetURL = targetURL;
		this.unsafe = unsafe;
		this.listenAddress = new ListenAddress(0, listenHostname, null);
	}

	private final SharedResourceLot tunnel;

	private final long targetId;
	
	private UrlRewriteConfig urlRewriteConfig = null;

	private final String targetDescription;
	private final URL targetURL;

	private final boolean unsafe;

	private ListenAddress listenAddress;

	public ListenAddress getListenAddress() {
		return listenAddress;
	}
	
	public URL getListenUrl() {
		return listenAddress.getListenUrl(Optional.of(this.getTargetURL().getFile()));
	}


	public String getTargetDomain() {
		return getTargetURL().getHost();
	}

	public int getTargetPort() {
		return Optional.of(getTargetURL().getPort()).map(p -> p <= 0 ? getTargetURL().getDefaultPort() : p).get();
	}

	public URL getTargetURL() {
		return targetURL;
	}

	public String getTargetProtocol() {
		return getTargetURL().getProtocol();
	}

	public String getTargetDescription() {
		return targetDescription;
	}

	public long getTargetId() {
		return targetId;
	}

	public SharedResourceLot getTunnel() {
		return tunnel;
	}
	
	public synchronized Function<String, String> getUrlRewriteFunction() {
		return y -> urlRewriteConfig.urlRewriteMap.getOrDefault(y, y);
	}

	public synchronized Function<String, String> getUrlReverseRewriteFunction() {
		return y -> urlRewriteConfig.urlReverseRewriteMap.getOrDefault(y, y);
	}

	public synchronized boolean isUnsafe() {
		return this.unsafe;
	}

	public void setListenAddress(ListenAddress listenAddress) {
		this.listenAddress = listenAddress;		
		this.urlRewriteConfig = buildUrlRewriteConfig(listenAddress, this.targetURL);
	}

	private static UrlRewriteConfig buildUrlRewriteConfig(ListenAddress listenAddress, URL targetUrl) {
		UrlRewriteConfig config = new UrlRewriteConfig();
		String tuu = listenAddress.getCloudProtocol() + "://" + listenAddress.getCloudHostname() + ":" + listenAddress.getListenPort();			
		int tPort = IOTools.getPort(targetUrl);
		String tProtocol = targetUrl.getProtocol();
		String tHost = targetUrl.getHost();
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
		return config;
	}

	public UrlRewriteConfig getUrlRewriteConfig() {
		return urlRewriteConfig;
	}

}