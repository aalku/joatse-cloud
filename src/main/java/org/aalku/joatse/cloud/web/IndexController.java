package org.aalku.joatse.cloud.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class IndexController {
	@GetMapping("/")
	public String greeting(Model model) {
		return "forward:/index.html";
	}
}
