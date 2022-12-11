package org.aalku.joatse.cloud.web;

import org.aalku.joatse.cloud.service.user.UserManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class IndexController {
	
	@Autowired
	private UserManager userManager;

	@GetMapping("/")
	public String index() {
		if (userManager.getAuthenticatedUser().map(u->u.isUser()).orElse(false)) {
			return "forward:/index.html";
		} else {
			return "forward:/unauthorized.html";
		}
	}
}
