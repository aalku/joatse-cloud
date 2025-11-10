package org.aalku.joatse.cloud.service.sharing.request;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.aalku.joatse.cloud.service.sharing.SharingManager.TunnelCreationResult;
import org.aalku.joatse.cloud.tools.net.AddressRange;
import org.json.JSONArray;
import org.json.JSONArray;
import org.json.JSONObject;

public class LotSharingRequest {
	private final UUID uuid = UUID.randomUUID();
	private final InetSocketAddress requesterAddress;
	private final Collection<TunnelRequestItem> items;
	private final Instant creationTime;
	private final CompletableFuture<TunnelCreationResult> future = new CompletableFuture<>();
	private final Collection<AddressRange> allowedAddressRanges;
	private final UUID preconfirmedUuid;
	private final boolean autoAuthorizeByHttpUrl;

	public LotSharingRequest(InetSocketAddress connectionRequesterAddress, Collection<TunnelRequestItem> tunnelItems, boolean autoAuthorizeByHttpUrl, UUID preconfirmedUuid) {
		this.requesterAddress = connectionRequesterAddress;
		this.items = new ArrayList<>(tunnelItems);
		this.creationTime = Instant.now();
		this.allowedAddressRanges = new ArrayList<>();
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

	public Collection<AddressRange> getAllowedAddressRanges() {
		return allowedAddressRanges;
	}
	
	/**
	 * @deprecated Use getAllowedAddressRanges() instead
	 */
	@Deprecated
	public Set<InetAddress> getAllowedAddresses() {
		return allowedAddressRanges.stream()
				.filter(AddressRange::isExact)
				.map(AddressRange::toInetAddress)
				.filter(Optional::isPresent)
				.map(Optional::get)
				.collect(Collectors.toSet());
	}

	public Collection<TunnelRequestItem> getItems() {
		return items;
	}

	public static LotSharingRequest fromJsonRequest(JSONObject js, InetSocketAddress connectionRequesterAddress) throws MalformedURLException {
		final UUID preconfirmedUuid = Optional.ofNullable(js.optString("preconfirmed")).filter(s->!s.isEmpty()).map(s->UUID.fromString(s)).orElse(null);
		final boolean autoAuthorizeByHttpUrl = js.optBoolean("autoAuthorizeByHttpUrl", false);

		Collection<TunnelRequestItem> items = fromJsonSharedResources(js);
		LotSharingRequest lotSharingRequest = new LotSharingRequest(connectionRequesterAddress, items, autoAuthorizeByHttpUrl, preconfirmedUuid);

		// Parse allowed addresses from JSON - supports both backward compatibility and new patterns
		JSONArray allowedAddressesArray = js.optJSONArray("allowedAddresses");
		if (allowedAddressesArray != null) {
			Collection<String> patterns = new ArrayList<>();
			for (int i = 0; i < allowedAddressesArray.length(); i++) {
				String addressPattern = allowedAddressesArray.getString(i);
				patterns.add(addressPattern);
			}
			lotSharingRequest.setAllowedAddressPatterns(patterns);
		}
		
		return lotSharingRequest;
	}

	public static Collection<TunnelRequestItem> fromJsonSharedResources(JSONObject js) throws MalformedURLException {
		Collection<TunnelRequestTcpItem> tcpTunnelReqs = fromJsonTcpTunnels(js);		
		Collection<TunnelRequestHttpItem> httpTunnelReqs = fromJsonHttpTunnels(js);		
		Optional<TunnelRequestTcpItem> socks5TunnelReq = fromJsonSocks5Tunnel(js);
		Collection<TunnelRequestCommandItem> commandTunnelReqs = fromJsonCommandTunnels(js);		
		
		Collection<TunnelRequestItem> items = new ArrayList<TunnelRequestItem>();
		socks5TunnelReq.ifPresent(x->items.add(x));
		items.addAll(tcpTunnelReqs);
		items.addAll(httpTunnelReqs);
		items.addAll(commandTunnelReqs);
		return items;
	}

	private static Collection<TunnelRequestCommandItem> fromJsonCommandTunnels(JSONObject js) {
		Collection<TunnelRequestCommandItem> items = new ArrayList<>();
		for (Object o: Optional.ofNullable(js.optJSONArray("commandTunnels")).orElseGet(()->new JSONArray())) {
			JSONObject jo = (JSONObject) o;
			long targetId = jo.optLong("targetId");
			String targetDescription = jo.optString("targetDescription");
			String targetHostname = jo.getString("targetHostname");
			int targetPort = jo.getInt("targetPort");
			String targetUser = jo.getString("targetUser");
			String[] command = jo.getJSONArray("command").toList().stream().map(x->x.toString()).collect(Collectors.toList()).toArray(new String[0]);
			// Generate default description if not provided
			String finalDescription = TunnelDescriptionUtils.getDefaultCommandDescription(targetDescription, command);
			items.add(new TunnelRequestCommandItem(targetId, finalDescription, targetHostname, targetPort, targetUser, command));
		}
		return items;
	}

	private static Optional<TunnelRequestTcpItem> fromJsonSocks5Tunnel(JSONObject js) {
		Optional<TunnelRequestTcpItem> item = Optional.ofNullable(js.optJSONArray("socks5Tunnel")).map(a->{
			JSONObject jo = a.getJSONObject(0);
			long targetId = jo.optLong("targetId");
			return new TunnelRequestTcpItem(targetId, "socks5", "socks5", 0);
		});
		return item;
	}

	private static Collection<TunnelRequestHttpItem> fromJsonHttpTunnels(JSONObject js) throws MalformedURLException {
		Collection<TunnelRequestHttpItem> items = new ArrayList<>();
		for (Object o: Optional.ofNullable(js.optJSONArray("httpTunnels")).orElseGet(()->new JSONArray())) {
			JSONObject jo = (JSONObject) o;
			long targetId = jo.optLong("targetId");
			String targetDescription = jo.optString("targetDescription");
			URL targetUrl = new URL(jo.optString("targetUrl"));
			boolean unsafe = jo.optBoolean("unsafe", false);
			boolean hideProxy = jo.optBoolean("hideProxy", false);
			// Generate default description if not provided
			String finalDescription = TunnelDescriptionUtils.getDefaultHttpDescription(targetDescription, targetUrl);
			items.add(new TunnelRequestHttpItem(targetId, finalDescription, targetUrl, unsafe, Optional.empty(), hideProxy)); // TODO
		}
		return items;
	}

	private static Collection<TunnelRequestTcpItem> fromJsonTcpTunnels(JSONObject js) {
		Collection<TunnelRequestTcpItem> items = new ArrayList<>();
		for (Object o: Optional.ofNullable(js.optJSONArray("tcpTunnels")).orElseGet(()->new JSONArray())) {
			JSONObject jo = (JSONObject) o;
			long targetId = jo.optLong("targetId");
			String targetDescription = jo.optString("targetDescription");
			String targetHostname = jo.optString("targetHostname");
			int targetPort = jo.getInt("targetPort");
			// Generate default description if not provided
			String finalDescription = TunnelDescriptionUtils.getDefaultTcpDescription(targetDescription, targetHostname, targetPort);
			items.add(new TunnelRequestTcpItem(targetId, finalDescription, targetHostname, targetPort));
		}
		return items;
	}

	public UUID getPreconfirmedUuid() {
		return preconfirmedUuid;
	}

	public boolean isAutoAuthorizeByHttpUrl() {
		return autoAuthorizeByHttpUrl;
	}

	public void setAllowedAddressRanges(Collection<AddressRange> ranges) {
		allowedAddressRanges.clear();
		allowedAddressRanges.addAll(ranges);
	}
	
	/**
	 * @deprecated Use setAllowedAddressRanges() instead
	 */
	@Deprecated
	public void setAllowedAddresses(Set<InetAddress> set) {
		allowedAddressRanges.clear();
		set.stream()
			.map(addr -> AddressRange.of(addr.getHostAddress()))
			.forEach(allowedAddressRanges::add);
	}
	
	/**
	 * Sets allowed address ranges from string patterns (supports *, CIDR, exact IPs).
	 * This method is used for JSON deserialization and database storage.
	 */
	public void setAllowedAddressPatterns(Collection<String> patterns) {
		allowedAddressRanges.clear();
		patterns.stream()
			.map(AddressRange::of)
			.forEach(allowedAddressRanges::add);
	}
	
	/**
	 * Gets allowed address patterns as strings for JSON serialization and database storage.
	 */
	public Collection<String> getAllowedAddressPatterns() {
		return allowedAddressRanges.stream()
				.map(AddressRange::toString)
				.collect(Collectors.toList());
	}


}