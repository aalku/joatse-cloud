package org.aalku.joatse.cloud.service.sharing.request;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.aalku.joatse.cloud.service.sharing.SharingManager.TunnelCreationResult;
import org.json.JSONArray;
import org.json.JSONObject;

public class LotSharingRequest {
	private final UUID uuid = UUID.randomUUID();
	private final InetSocketAddress requesterAddress;
	private final Collection<TunnelRequestItem> items;
	private final Instant creationTime;
	private final CompletableFuture<TunnelCreationResult> future = new CompletableFuture<>();
	private final Set<InetAddress> allowedAddresses;
	private final UUID preconfirmedUuid;
	private final boolean autoAuthorizeByHttpUrl;

	public LotSharingRequest(InetSocketAddress connectionRequesterAddress, Collection<TunnelRequestItem> tunnelItems, boolean autoAuthorizeByHttpUrl, UUID preconfirmedUuid) {
		this.requesterAddress = connectionRequesterAddress;
		this.items = new ArrayList<>(tunnelItems);
		this.creationTime = Instant.now();
		this.allowedAddresses = new LinkedHashSet<>();
		this.preconfirmedUuid = preconfirmedUuid;
		this.autoAuthorizeByHttpUrl = autoAuthorizeByHttpUrl;
	}

	public InetSocketAddress getRequesterAddress() {
		return requesterAddress;
	}

	public Instant getCreationTime() {
		return creationTime;
	}

	public CompletableFuture<TunnelCreationResult> getFuture() {
		return future;
	}

	public UUID getUuid() {
		return uuid;
	}

	public Set<InetAddress> getAllowedAddresses() {
		return allowedAddresses;
	}

	public Collection<TunnelRequestItem> getItems() {
		return items;
	}

	public static LotSharingRequest fromJson(JSONObject js, InetSocketAddress connectionRequesterAddress) throws MalformedURLException {
		Collection<TunnelRequestTcpItem> tcpTunnelReqs = new ArrayList<TunnelRequestTcpItem>();
		for (Object o: Optional.ofNullable(js.optJSONArray("tcpTunnels")).orElseGet(()->new JSONArray())) {
			JSONObject jo = (JSONObject) o;
			long targetId = jo.optLong("targetId");
			String targetDescription = jo.optString("targetDescription");
			String targetHostname = jo.optString("targetHostname");
			int targetPort = jo.getInt("targetPort");
			tcpTunnelReqs.add(new TunnelRequestTcpItem(targetId, targetDescription, targetHostname, targetPort));
		}
		
		Collection<TunnelRequestHttpItem> httpTunnelReqs = new ArrayList<TunnelRequestHttpItem>();
		for (Object o: Optional.ofNullable(js.optJSONArray("httpTunnels")).orElseGet(()->new JSONArray())) {
			JSONObject jo = (JSONObject) o;
			long targetId = jo.optLong("targetId");
			String targetDescription = jo.optString("targetDescription");
			URL targetUrl = new URL(jo.optString("targetUrl"));
			boolean unsafe = jo.optBoolean("unsafe", false);
			httpTunnelReqs.add(new TunnelRequestHttpItem(targetId, targetDescription, targetUrl, unsafe, Optional.empty())); // TODO
		}
		
		Optional.ofNullable(js.optJSONArray("socks5Tunnel")).ifPresent(a->{
			JSONObject jo = a.getJSONObject(0);
			long targetId = jo.optLong("targetId");
			tcpTunnelReqs.add(new TunnelRequestTcpItem(targetId, "socks5", "socks5", 0));
		});
		
		Collection<TunnelRequestItem> items = new ArrayList<TunnelRequestItem>();
		items.addAll(tcpTunnelReqs);
		items.addAll(httpTunnelReqs);
		
		final UUID preconfirmedUuid = Optional.ofNullable(js.optString("preconfirmed")).filter(s->!s.isEmpty()).map(s->UUID.fromString(s)).orElse(null);
		final boolean autoAuthorizeByHttpUrl = js.optBoolean("autoAuthorizeByHttpUrl", false);
		LotSharingRequest lotSharingRequest = new LotSharingRequest(connectionRequesterAddress, items, autoAuthorizeByHttpUrl, preconfirmedUuid);

		// TODO allowed addresses from json
		return lotSharingRequest;
	}

	public UUID getPreconfirmedUuid() {
		return preconfirmedUuid;
	}

	public boolean isAutoAuthorizeByHttpUrl() {
		return autoAuthorizeByHttpUrl;
	}

	public void setAllowedAddresses(Set<InetAddress> set) {
		allowedAddresses.addAll(set);
		allowedAddresses.retainAll(set);
	}


}