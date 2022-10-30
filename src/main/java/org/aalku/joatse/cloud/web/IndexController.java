package org.aalku.joatse.cloud.web;

import org.aalku.joatse.cloud.service.user.JoatseUser;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class IndexController {
	@GetMapping("/")
	public String greeting(Model model) {
		JoatseUser user = (JoatseUser) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
		model.addAttribute("name", user.getNameToAddress());
		return "index.html";
	}
}
