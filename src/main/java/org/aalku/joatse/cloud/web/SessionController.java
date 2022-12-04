package org.aalku.joatse.cloud.web;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.aalku.joatse.cloud.config.ListenerConfigurationDetector;
import org.aalku.joatse.cloud.service.CloudTunnelService.JoatseTunnel;
import org.aalku.joatse.cloud.service.CloudTunnelService.JoatseTunnel.TcpTunnel;
import org.aalku.joatse.cloud.service.HttpProxy.HttpTunnel;
import org.aalku.joatse.cloud.service.JWSSession;
import org.aalku.joatse.cloud.service.JoatseWsHandler;
import org.aalku.joatse.cloud.service.user.UserManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
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
	
	private List<InetAddress> getRemoteAddressess(HttpServletRequest request) {
		String allowedAddress = request.getRemoteAddr();
		List<InetAddress> addresses;
		try {
			if (InetAddress.getByName(allowedAddress).isLoopbackAddress()) {
				addresses = Arrays.asList(InetAddress.getAllByName("localhost"));
			} else {
				addresses = Arrays.asList(InetAddress.getAllByName(allowedAddress));
			}
		} catch (UnknownHostException e) {
			throw new RuntimeException("Internal error", e);
		}
		return addresses;
	}

	@GetMapping("/sessions")
	public Map<String, Object> getSessions(HttpServletRequest request) {
		Collection<JWSSession> sessions = wsHandler.getSessions(userManager.getAuthenticatedUser().orElseThrow());
		Map<String, Object> res = new LinkedHashMap<>();
		res.put("sessions", sessions.stream().map(s->{
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("uuid", s.getTunnelUUID());
			JoatseTunnel t = s.getTunnel();
			m.put("requesterAddress", t.getRequesterAddress());
			m.put("allowedAddress", t.getAllowedAddress());
			m.put("creationTime", t.getCreationTime());			
			boolean allowed = getRemoteAddressess(request).stream().anyMatch(a->t.getAllowedAddress().contains(a));
			m.put("addressIsAllowed", allowed);
			
			Collection<TcpTunnel> tcpItems = t.getTcpItems();
			List<Map<String, Object>> atcp = new ArrayList<>(tcpItems.size());
			if (!tcpItems.isEmpty()) {
				for (JoatseTunnel.TcpTunnel x: tcpItems) {
					Map<String, Object> item = new LinkedHashMap<>();
					item.put("targetId", x.targetId);
					item.put("targetDescription", x.targetDescription);
					item.put("targetHostname", x.targetHostname);
					item.put("targetPort", x.targetPort);
					item.put("listenHostname", webListenerConfiguration.getPublicHostname());
					item.put("listenPort", x.listenPort);
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
					item.put("listenHostname", x.getCloudHostname());
					item.put("listenPort", x.getListenPort());
					ahttp.add(item);
				}
			}
			m.put("httpItems", ahttp);
			return m;
		}).collect(Collectors.toList()));
		return res;
	}

}
