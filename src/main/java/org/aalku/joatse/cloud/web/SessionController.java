package org.aalku.joatse.cloud.web;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.aalku.joatse.cloud.config.WebListenerConfigurationDetector;
import org.aalku.joatse.cloud.service.CloudTunnelService.JoatseTunnel;
import org.aalku.joatse.cloud.service.CloudTunnelService.JoatseTunnel.TcpItem;
import org.aalku.joatse.cloud.service.JWSSession;
import org.aalku.joatse.cloud.service.JoatseWsHandler;
import org.aalku.joatse.cloud.service.user.JoatseUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SessionController {
	
	@Autowired
	private JoatseWsHandler wsHandler;
	
	@Autowired
	private WebListenerConfigurationDetector webListenerConfiguration;

	@GetMapping("/sessions")
	public Map<String, Object> getSessions() {
		JoatseUser user = (JoatseUser) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
		Collection<JWSSession> sessions = wsHandler.getSessions(user);
		Map<String, Object> res = new LinkedHashMap<>();
		res.put("sessions", sessions.stream().map(s->{
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("uuid", s.getTunnelUUID());
			JoatseTunnel t = s.getTunnel();
			m.put("requesterAddress", t.getRequesterAddress());
			m.put("allowedAddress", t.getAllowedAddress());
			m.put("creationTime", t.getCreationTime());
			Collection<TcpItem> tcpItems = t.getTcpItems();
			List<Map<String, Object>> atcp = new ArrayList<>(tcpItems.size());
			for (JoatseTunnel.TcpItem x: tcpItems) {
				Map<String, Object> mtcp = new LinkedHashMap<>();
				mtcp.put("targetId", x.targetId);
				mtcp.put("targetDescription", x.targetDescription);
				mtcp.put("targetHostname", x.targetHostname);
				mtcp.put("targetPort", x.targetPort);
				mtcp.put("listenHostname", webListenerConfiguration.getPublicHostname());
				mtcp.put("listenPort", x.listenPort);
				atcp.add(mtcp);
			}
			m.put("tcpItems", atcp);
			return m;
		}).collect(Collectors.toList()));
		return res;
	}

}
