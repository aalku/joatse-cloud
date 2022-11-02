package org.aalku.joatse.cloud.web;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.aalku.joatse.cloud.service.CloudTunnelService.JoatseTunnel;
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

	@GetMapping("/sessions")
	public Map<String, Object> getSessions() {
		JoatseUser user = (JoatseUser) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
		Collection<JWSSession> sessions = wsHandler.getSessions(user);
		Map<String, Object> res = new LinkedHashMap<>();
		res.put("sessions", sessions.stream().map(s->{
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("uuid", s.getTunnelUUID());
			JoatseTunnel t = s.getTunnel();
//			m.put("targetHostId", t.getTargetHostId());
//			m.put("targetHostname", t.getTargetHostname());
//			m.put("targetPortDescription", t.getTargetPortDescription());
//			m.put("targetPort", t.getTargetPort());
			m.put("requesterAddress", t.getRequesterAddress());
			m.put("allowedAddress", t.getAllowedAddress());
//			m.put("publicAddress", t.getPublicAddress());
//			m.put("cloudListenPort", t.getCloudListenPort());
			m.put("creationTime", t.getCreationTime());
			return m;
		}).collect(Collectors.toList()));
		return res;
	}

}
