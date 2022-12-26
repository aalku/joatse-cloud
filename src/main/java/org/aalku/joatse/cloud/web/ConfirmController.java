package org.aalku.joatse.cloud.web;

import java.io.Serializable;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.aalku.joatse.cloud.config.WebSecurityConfiguration;
import org.aalku.joatse.cloud.service.CloudTunnelService;
import org.aalku.joatse.cloud.service.CloudTunnelService.TunnelRequest;
import org.aalku.joatse.cloud.service.user.UserManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
	
	public static final String POST_LOGIN_CONFIRM_HASH = "/postLogin/confirmHash";

	public final static String CONFIRM_SESSION_KEY_HASH = "CONFIRM_SESSION_KEY_HASH";
	
	private Logger log = LoggerFactory.getLogger(ConfirmController.class);
	
	@Autowired
	private CloudTunnelService cloudTunnelService;
	
	@Autowired
	private UserManager userManager;

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
	@GetMapping(POST_LOGIN_CONFIRM_HASH)
	public String postLogin(
			@SessionAttribute(name = ConfirmController.CONFIRM_SESSION_KEY_HASH, required = true) HashContainer hash) {
		userManager.requireRole("JOATSE_USER");
		if (null != hash) {
			return "forward:/CF/A"; // With hash
		} else {
			return "redirect:/"; // Normal login
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
		
		if (userManager.hasRole("JOATSE_USER")) {
			return new RedirectView("/CF/A");
		} else {
			return new RedirectView(WebSecurityConfiguration.PATH_LOGIN_FORM);
		}
		
	}
	
	/**
	 * Logged in and with hash
	 * @throws UnknownHostException 
	 */
	@GetMapping("/CF/A")  
	public String confirm3(Model model, HttpServletRequest request,
			@SessionAttribute(name = ConfirmController.CONFIRM_SESSION_KEY_HASH, required = true) HashContainer hashContainer) throws UnknownHostException {
		userManager.requireRole("JOATSE_USER");
		//log.info("confirm3");
		request.getSession(false).removeAttribute(CONFIRM_SESSION_KEY_HASH);
		if (hashContainer.receivedInstant.plusSeconds(300).isBefore(Instant.now())) {
			throw new RuntimeException("Expired hash"); // TODO show it nicely
		}
		TunnelRequest tunnelRequest = cloudTunnelService
				.getTunnelRequest(UUID.fromString(hashContainer.hash.replaceFirst("^#+", "")));
		if (tunnelRequest != null) {
			InetAddress address = getRemoteAddresses(request);
			tunnelRequest.setAllowedAddresses(Arrays.asList(address));
			model.addAttribute("hash", hashContainer.hash);
			model.addAttribute("tunnelRequest", tunnelRequest);
			model.addAttribute("allowedAddress", formatAllowedAddress(tunnelRequest.getAllowedAddresses()));
			model.addAttribute("uuid", tunnelRequest.getUuid().toString());
		}
		return "cf3.html";
	}


	private InetAddress getRemoteAddresses(HttpServletRequest request) {
		String remoteAddress = request.getRemoteAddr();
		try {
			return InetAddress.getByName(remoteAddress);
		} catch (UnknownHostException e) {
			throw new RuntimeException("Internal error", e);
		}
	}

	@PostMapping("/CF/A")  
	public String confirm4(Model model, @RequestParam(name = "uuid") String sUuid) {
		userManager.requireRole("JOATSE_USER");
		//log.info("confirm4");
		UUID uuid = UUID.fromString(sUuid);
		cloudTunnelService.acceptTunnelRequest(uuid, userManager.getAuthenticatedUser().orElseThrow());
		return "redirect:/";
	}
	
	private String formatAllowedAddress(Collection<InetAddress> collection) {
		StringBuilder sb = new StringBuilder();
		for (InetAddress a: collection) {
			if (sb.length() > 0) {
				sb.append(", ");
			}
			sb.append(a);
			String allowedHostname = reverseDns(a);
			sb.append("/").append(allowedHostname);
		}
		return sb.toString();
	}


	private String reverseDns(InetAddress address) {
		try {
			String hostName = address.getHostName();
			Optional<InetAddress> o = Stream.of(InetAddress.getAllByName("localhost")).filter(x->x.equals(address)).findAny();
			if (o.isPresent()) {
				return "localhost";
			}
			return hostName;
		} catch (Exception e) {
			return null;
		}
	}

}
