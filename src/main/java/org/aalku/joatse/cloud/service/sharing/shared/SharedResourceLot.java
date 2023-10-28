package org.aalku.joatse.cloud.service.sharing.shared;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.AsynchronousSocketChannel;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.aalku.joatse.cloud.service.sharing.http.HttpEndpointGenerator;
import org.aalku.joatse.cloud.service.sharing.http.HttpTunnel;
import org.aalku.joatse.cloud.service.sharing.http.ListenAddress;
import org.aalku.joatse.cloud.service.sharing.request.LotSharingRequest;
import org.aalku.joatse.cloud.service.sharing.request.TunnelRequestHttpItem;
import org.aalku.joatse.cloud.service.sharing.request.TunnelRequestTcpItem;
import org.aalku.joatse.cloud.service.user.vo.JoatseUser;
import org.json.JSONArray;
import org.json.JSONObject;

public class SharedResourceLot {

	private final JoatseUser owner;
	private final UUID uuid;
	private final String cloudPublicHostname;
	private Collection<InetAddress> allowedAddresses;
	private final InetSocketAddress requesterAddress;
	private final Instant creationTime;
	/**
	 * Handler for tcp or http(s) connections that are handled as tcp at this point
	 */
	private BiConsumer<Long, AsynchronousSocketChannel> tcpConnectionListener;
	private Collection<TcpTunnel> tcpItems = new ArrayList<>(1);
	private Collection<HttpTunnel> httpItems = new ArrayList<>(1);

	public SharedResourceLot(JoatseUser owner, LotSharingRequest request, String cloudPublicHostname) {
		this.owner = owner;
		this.uuid = request.getUuid();
		this.cloudPublicHostname = cloudPublicHostname;
		this.requesterAddress = request.getRequesterAddress();
		this.allowedAddresses = request.getAllowedAddresses();
		this.creationTime = request.getCreationTime();
		
		List<TunnelRequestTcpItem> rItems = request.getItems().stream().filter(x -> x instanceof TunnelRequestTcpItem)
				.map(x -> (TunnelRequestTcpItem) x).collect(Collectors.toList());
		for (TunnelRequestTcpItem r: rItems) {
			this.addTcpItem(r);
		}
		
		List<TunnelRequestHttpItem> httpItems = request.getItems().stream()
				.filter(i -> (i instanceof TunnelRequestHttpItem)).map(i->(TunnelRequestHttpItem)i).collect(Collectors.toList());

		for (TunnelRequestHttpItem r : httpItems) {
			this.addHttpItem(r);
		}

		
	}

	public void setTcpConnectionConsumer(BiConsumer<Long, AsynchronousSocketChannel> tcpConnectionListener) {
		this.tcpConnectionListener = tcpConnectionListener;
	}

	public Collection<InetAddress> getAllowedAddresses() {
		synchronized (allowedAddresses) {
			return new LinkedHashSet<>(allowedAddresses);
		}
	}
	
	public void addAllowedAddress(InetAddress ip) {
		synchronized (allowedAddresses) {
			allowedAddresses.add(ip);
		}		
	}

	public InetSocketAddress getRequesterAddress() {
		return requesterAddress;
	}

	public UUID getUuid() {
		return uuid;
	}

	public Instant getCreationTime() {
		return creationTime;
	}

	public JoatseUser getOwner() {
		return owner;
	}

	private void addTcpItem(TunnelRequestTcpItem r) {
		TcpTunnel i = new TcpTunnel(this, r.targetId, r.targetDescription, r.targetHostname, r.targetPort);
		tcpItems.add(i);
	}
	
	private void addHttpItem(TunnelRequestHttpItem r) {
		httpItems.add(new HttpTunnel(this, r.targetId, r.targetDescription, r.getTargetUrl(), r.isUnsafe(), r.getListenHostname()));
	}

	public TcpTunnel getTcpItem(long targetId) {
		return tcpItems.stream().filter(i->i.targetId==targetId).findAny().orElse(null);
	}

	public Collection<TcpTunnel> getTcpItems() {
		return tcpItems;
	}

	public String getCloudPublicHostname() {
		return cloudPublicHostname;
	}

	public Collection<HttpTunnel> getHttpItems() {
		return httpItems;
	}

	public HttpTunnel getHttpItem(long targetId) {
		return getHttpItems().stream().filter(i->i.getTargetId()==targetId).findAny().orElse(null);
	}

	/**
	 * Create a tunneled tcp connection.
	 * 
	 * It can tunnel tcp tunnels or other tunnels running over tcp so targetId might
	 * or might not represent a tcpTunnel.
	 * 
	 * @param targetId
	 * @param channel
	 */
	public void tunnelTcpConnection(long targetId, AsynchronousSocketChannel channel) {
		tcpConnectionListener.accept(targetId, channel);
	}

	public void selectTcpPorts(Collection<Integer> tcpOpenPorts) {
		Iterator<Integer> it = tcpOpenPorts.iterator();
		tcpItems.forEach(i->{
			if (it.hasNext()) {
				i.setListenPort(it.next());
			} else {
				throw new RuntimeException("Not enough open TCP ports (" + tcpOpenPorts.size() + ") for this share size (" + tcpItems.size() + ")" );
			}
		});
	}

	public void selectHttpEndpoints(HttpEndpointGenerator httpEndpointGenerator) {
		LinkedHashSet<ListenAddress> forbiddenAddresses = new LinkedHashSet<ListenAddress>();
		httpItems.forEach(i->{
			String askedCloudHostname = Optional.ofNullable(i.getListenAddress()).map(x -> x.getCloudHostname())
					.orElse(null);
			ListenAddress listenAddress = httpEndpointGenerator.generateListenAddress(i, forbiddenAddresses,
					askedCloudHostname);
			i.setListenAddress(listenAddress);
			forbiddenAddresses.add(listenAddress);
		});
	}

	public JSONObject toJsonSharedResources() {
		JSONObject res = new JSONObject();
		
		Collection<HttpTunnel> httpItems = Optional.ofNullable(this.httpItems).orElse(Collections.emptyList());
		if (httpItems.size() > 0) {
			JSONArray a = new JSONArray();
			for (HttpTunnel i: httpItems) {
				JSONObject o = new JSONObject();
				o.put("targetDescription", i.getTargetDescription());
				o.put("targetUrl", i.getTargetURL());
				o.put("unsafe", String.valueOf(i.isUnsafe()));
				a.put(o);
			}
			res.put("httpTunnels", a);
		}
		Collection<TcpTunnel> tcpItems = Optional.ofNullable(this.tcpItems).orElse(Collections.emptyList());
		if (tcpItems.size() > 0) {
			Predicate<TcpTunnel> isSocksPredicate = i->i.targetHostname.equals("socks5") && i.targetPort == 0;
			
			Optional<TcpTunnel> socks5Item = tcpItems.stream().filter(isSocksPredicate).findAny();
			if (socks5Item.isPresent()) {
				JSONArray a = new JSONArray();
//				TcpTunnel i = socks5Item.get();
				JSONObject o = new JSONObject();
				// Nothing?
				a.put(o);
				res.put("socks5Tunnel", a);
			}

			Collection<TcpTunnel> normalTcpItems = tcpItems.stream().filter(isSocksPredicate.negate())
					.collect(Collectors.toList());
			if (normalTcpItems.size() > 0) {
				JSONArray a = new JSONArray();
				for (TcpTunnel i: normalTcpItems) {
					JSONObject o = new JSONObject();
					o.put("targetDescription", i.targetDescription);
					o.put("targetHostname", i.targetHostname);
					o.put("targetPort", i.targetPort);
					a.put(o);
				}
				res.put("tcpTunnels", a);
			}
		}
		return res;
	}
}