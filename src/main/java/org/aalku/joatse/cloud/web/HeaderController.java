package org.aalku.joatse.cloud.web;

import java.util.Map;

import org.aalku.joatse.cloud.service.user.UserManager;
import org.aalku.joatse.cloud.service.user.vo.JoatseUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class HeaderController {
	
	@SuppressWarnings("unused")
	private Logger log = LoggerFactory.getLogger(HeaderController.class);
	
	@Autowired
	private UserManager userManager;

	@GetMapping("/user")
	@ResponseBody
	public Map<String, Object> getUser() {
		JoatseUser user = userManager.getAuthenticatedUser().orElse(null);
		if (user != null) {
			return Map.of(
					"nameToAddress", user.getUsername(),
					"isAdmin", user.isAdmin()
				);
		} else {
			throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "User not logged in");
		}
	}
}
