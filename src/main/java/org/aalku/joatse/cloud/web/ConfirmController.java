package org.aalku.joatse.cloud.web;

import java.io.Serializable;
import java.net.InetAddress;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.aalku.joatse.cloud.config.WebListenerConfigurationDetector;
import org.aalku.joatse.cloud.config.WebSecurityConfiguration;
import org.aalku.joatse.cloud.service.CloudTunnelService;
import org.aalku.joatse.cloud.service.CloudTunnelService.TunnelDefinition;
import org.aalku.joatse.cloud.service.CloudTunnelService.TunnelRequest;
import org.aalku.joatse.cloud.service.user.JoatseUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.SessionAttribute;
import org.springframework.web.servlet.View;
import org.springframework.web.servlet.view.RedirectView;

import jakarta.servlet.http.HttpServletRequest;

@Controller
public class ConfirmController {
	
	public final static String CONFIRM_SESSION_KEY_HASH = "CONFIRM_SESSION_KEY_HASH";
	
	private Logger log = LoggerFactory.getLogger(ConfirmController.class);
	
	@Autowired
	private WebListenerConfigurationDetector webListenerConfiguration;
	
	@Autowired
	private CloudTunnelService cloudTunnelService;
	
	public static class HashContainer implements Serializable {
		private static final long serialVersionUID = 1L;
		private String hash;
		private Instant receivedInstant;
		public HashContainer(String hash) {
			this.hash = hash;
			this.receivedInstant = Instant.now();
		}
	}
	
	/**
	 * You just logged in
	 * @return
	 */
	@GetMapping("/postLogin") // FIXME // TODO  
	public View postLogin(
			@SessionAttribute(name = ConfirmController.CONFIRM_SESSION_KEY_HASH, required = false) HashContainer hash) {
		if (!SecurityContextHolder.getContext().getAuthentication().isAuthenticated()) {
			throw new AssertionError();
		}
		log.info("postLogin");
		if (null != hash) {
			return new RedirectView("/CF/A"); // With hash
		} else {
			return new RedirectView("/"); // Normal login
		}
	}


	/**
	 * First step to confirm. The html will post us the hash. No matter if authenticated or not.
	 * @return
	 */
	@GetMapping("/CF")  
	public String confirm1(Model model) {
		//log.info("confirm1");
		return "cf1.html";
	}
	
	/**
	 * If logged it then skip to CF/3, else redirect to login after saving the hash
	 */
	@PostMapping("/CF/2")
	public View confirm2(HttpServletRequest request, @RequestParam(required = true, name = "hash") String hash) {
		
		//log.info("confirm2");
		request.getSession(true).setAttribute(CONFIRM_SESSION_KEY_HASH, new HashContainer(hash));
		
		if (SecurityContextHolder.getContext().getAuthentication().isAuthenticated()) {
			return new RedirectView("/CF/A");
		} else {
			return new RedirectView(WebSecurityConfiguration.PATH_LOGIN_FORM);
		}
		
	}
	
	/**
	 * Logged in and with hash
	 */
	@GetMapping("/CF/A")  
	public String confirm3(Model model, HttpServletRequest request,
			@SessionAttribute(name = ConfirmController.CONFIRM_SESSION_KEY_HASH, required = true) HashContainer hashContainer) {
		//log.info("confirm3");
		request.getSession(false).removeAttribute(CONFIRM_SESSION_KEY_HASH);
		if (!SecurityContextHolder.getContext().getAuthentication().isAuthenticated()) {
			throw new AssertionError();
		}
		if (hashContainer.receivedInstant.plusSeconds(300).isBefore(Instant.now())) {
			throw new RuntimeException("Expired hash"); // TODO show it nicely
		}
		TunnelRequest tunnelRequest = cloudTunnelService
				.getTunnelRequest(UUID.fromString(hashContainer.hash.replaceFirst("^#+", "")));
		if (tunnelRequest != null) {
			String allowedAddress = request.getRemoteAddr();
			tunnelRequest.setAllowedAddress(allowedAddress);
			model.addAttribute("hash", hashContainer.hash);
			model.addAttribute("tunnelRequest", tunnelRequest);
			model.addAttribute("allowedAddress", formatAllowedAddress(allowedAddress));
			model.addAttribute("uuid", tunnelRequest.getUuid().toString());
		}
		return "cf3.html";
	}

	@PostMapping("/CF/A")  
	public String confirm4(Model model, @RequestParam(name = "uuid") String sUuid) {
		//log.info("confirm4");
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (!auth.isAuthenticated()) {
			throw new AssertionError();
		}
		UUID uuid = UUID.fromString(sUuid);
		cloudTunnelService.acceptTunnelRequest(uuid, (JoatseUser) auth.getPrincipal());
		return viewTunnel(model, sUuid);
	}
	
	@GetMapping("/viewTunnel")
	public String viewTunnel(Model model, @RequestParam(name = "uuid") String sUuid) {
		TunnelDefinition tunnel = cloudTunnelService.getTunnel(UUID.fromString(sUuid));
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (tunnel != null && auth.getPrincipal().equals(tunnel.getOwner())) {
			model.addAttribute("uuid", sUuid);
			model.addAttribute("tunnel", tunnel);
			model.addAttribute("allowedAddress", formatAllowedAddress(tunnel.getAllowedAddress()));
			model.addAttribute("cloudPublicHostname", webListenerConfiguration.getPublicHostname());
		}
		return "viewTunnel.html";
	}


	private String formatAllowedAddress(String allowedAddress) {
		String allowedHostname = reverseDns(allowedAddress);
		if (!allowedAddress.equalsIgnoreCase(allowedHostname)) {
			allowedAddress += " / (" + allowedHostname + ")";
		}
		return allowedAddress;
	}


	private String reverseDns(String remoteAddr) {
		try {
			InetAddress address = InetAddress.getByName(remoteAddr);
			String hostName = address.getHostName();
			if (hostName.equalsIgnoreCase(remoteAddr)) {
				Optional<InetAddress> o = Stream.of(InetAddress.getAllByName("localhost")).filter(x->x.equals(address)).findAny();
				if (o.isPresent()) {
					return "localhost";
				}
			}
			return hostName;
		} catch (Exception e) {
			return null;
		}
	}

}
