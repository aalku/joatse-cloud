package org.aalku.joatse.cloud.web;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.aalku.joatse.cloud.config.ListenerConfigurationDetector;
import org.aalku.joatse.cloud.service.sharing.http.HttpTunnel;
import org.aalku.joatse.cloud.service.sharing.shared.SharedResourceLot;
import org.aalku.joatse.cloud.service.sharing.shared.TcpTunnel;
import org.aalku.joatse.cloud.service.JWSSession;
import org.aalku.joatse.cloud.service.JoatseWsHandler;
import org.aalku.joatse.cloud.service.user.UserManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

@RestController
public class SessionController {
	
	@Autowired
	private JoatseWsHandler wsHandler;
	
	@Autowired
	private ListenerConfigurationDetector webListenerConfiguration;
	
	@Autowired
	private UserManager userManager;
	
	private InetAddress getRemoteAddress(HttpServletRequest request) {
		try {
			return InetAddress.getByName(request.getRemoteAddr());
		} catch (UnknownHostException e) {
			throw new RuntimeException("Internal error", e);
		}
	}
	
	@PutMapping("/allowedIPs")
	@ResponseBody
	public Map<String, Object> addAllowedIp(HttpServletRequest request, @RequestBody Map<String, Object> payload) {
		Map<String, Object> res = new LinkedHashMap<>();
		UUID uuid = Optional.ofNullable((String) payload.get("session")).map(u->UUID.fromString(u)).get();
		Optional<SharedResourceLot> sharedResourceLotOptional = getSession(uuid);
		if (sharedResourceLotOptional.isPresent()) {
			InetAddress ip = getRemoteAddress(request);
			if (ip.isLoopbackAddress()) {
				try {
					for (InetAddress ip2: InetAddress.getAllByName("localhost")) {
						sharedResourceLotOptional.get().addAllowedAddress(ip2);					
					}
				} catch (UnknownHostException e) {
					throw new RuntimeException(e); // Impossible
				}
			} else {
				sharedResourceLotOptional.get().addAllowedAddress(ip);
			}
			res.put("result", "success");
		} else {
			throw new RuntimeException("Unknown session");
		}
		return res;
	}

	@GetMapping("/sessions")
	public Map<String, Object> getSessions(HttpServletRequest request) {
		Collection<JWSSession> sessions = wsHandler.getSessions(userManager.getAuthenticatedUser().orElseThrow());
		Map<String, Object> res = new LinkedHashMap<>();
		res.put("sessions", sessions.stream().map(s->{
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("uuid", s.getTunnelUUID());
			SharedResourceLot t = s.getSharedResourceLot();
			m.put("requesterAddress", t.getRequesterAddress());
			m.put("allowedAddress", t.getAllowedAddresses().stream().map(i->i.getHostAddress()).collect(Collectors.toSet()));
			m.put("creationTime", t.getCreationTime());			
			InetAddress remoteAddress = getRemoteAddress(request);
			boolean allowed = t.getAllowedAddresses().contains(remoteAddress);
			m.put("addressIsAllowed", allowed);
			
			Collection<TcpTunnel> tcpItems = t.getTcpItems();
			List<Map<String, Object>> atcp = new ArrayList<>(tcpItems.size());
			if (!tcpItems.isEmpty()) {
				for (TcpTunnel x: tcpItems) {
					Map<String, Object> item = new LinkedHashMap<>();
					item.put("targetId", x.targetId);
					item.put("targetDescription", x.targetDescription);
					item.put("targetHostname", x.targetHostname);
					item.put("targetPort", x.targetPort);
					item.put("listenHostname", webListenerConfiguration.getPublicHostname());
					item.put("listenPort", x.getListenPort());
					atcp.add(item);
				}
			}
			m.put("tcpItems", atcp);
			
			Collection<HttpTunnel> httpItems = t.getHttpItems();
			List<Map<String, Object>> ahttp = new ArrayList<>(httpItems.size());
			if (!httpItems.isEmpty()) {
				for (HttpTunnel x: httpItems) {
					Map<String, Object> item = new LinkedHashMap<>();
					item.put("targetId", x.getTargetId());
					item.put("targetDescription", x.getTargetDescription());
					item.put("targetUrl", x.getTargetURL().toString());
					item.put("listenUrl", x.getListenUrl());
					ahttp.add(item);
				}
			}
			m.put("httpItems", ahttp);
			return m;
		}).collect(Collectors.toList()));
		return res;
	}

	Optional<SharedResourceLot> getSession(UUID uuid) {
		Optional<SharedResourceLot> sharedResourceLotOptional = wsHandler
				.getSessions(userManager.getAuthenticatedUser().orElseThrow()).stream()
				.map(s -> s.getSharedResourceLot()).filter(srl -> srl.getUuid().equals(uuid)).findAny();
		return sharedResourceLotOptional;
	}

}
