package org.aalku.joatse.cloud.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import org.aalku.joatse.cloud.service.user.UserManager;
import org.aalku.joatse.cloud.service.user.vo.JoatseUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

@Secured("ROLE_JOATSE_ADMIN")
@Controller
public class AdminUserController {
	
	@Autowired
	private UserManager userManager;
	
	@GetMapping("/admin/users")
	public String greeting(Model model) {
		return "forward:/admin/users.html";
	}
	
	@GetMapping("/admin/users/list")
	@ResponseBody
	public Map<String, Object> listUsers() {
		Map<String, Object> res = new LinkedHashMap<>();
		ArrayList<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
		for (JoatseUser user: userManager.allUsers()) {
			List<String> roles = user.getAuthorities().stream().map(a -> a.getAuthority())
					.filter(a -> a.startsWith("ROLE_JOATSE_")).collect(Collectors.toList());
			String role = "USER";
			role = roles.contains("ROLE_JOATSE_ADMIN") ? "ADMIN" : role;
			if (role != null) {
				list.add(Map.of("UUID", user.getUuid().toString(), "login", user.getUsername(), "canDelete",
						!user.getUsername().equals("admin"), "role", role));
			}
		}
		res.put("users", list);
		return res;
	}

	@PostMapping("/admin/users")
	@ResponseBody
	public Map<String, Object> postUser(@RequestBody Map<String, Object> userMap) {
		String login = Optional.ofNullable((String) userMap.get("login")).get();
		Optional<String> password = Optional.ofNullable((String) userMap.get("password"));
		String role = Optional.ofNullable((String) userMap.get("role")).get();
		JoatseUser user = JoatseUser.newLocalUser(login, true);
		password.ifPresent(p->user.setPassword(PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(p)));
		if (role.equals("ADMIN")) {
			user.addAuthority(new SimpleGrantedAuthority("ROLE_JOATSE_ADMIN"));
		}
		userManager.createUser(user);
		return Map.of("result", "User created OK");
	}
	
	@PutMapping("/admin/users")
	@ResponseBody
	public Map<String, Object> putUser(@RequestBody Map<String, Object> userMap) {
		UUID uuid = Optional.ofNullable((String) userMap.get("UUID")).map(u->UUID.fromString(u)).get();
		JoatseUser user = userManager.loadUserByUUID(uuid);
		if (user == null) {
			throw new IllegalArgumentException("User not found");
		}
		String login = Optional.ofNullable((String) userMap.get("login")).get();
		if (!user.getUsername().equals(login)) {
			user.setUserName(login);
			user.setNeedEmailConfirmation();
		}
		Optional<String> password = Optional.ofNullable((String) userMap.get("password"));
		String role = Optional.ofNullable((String) userMap.get("role")).get();
		if (password.isPresent()) {
			user.setPassword(PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(password.get()));
		}
		if (role.equals("ADMIN")) {
			user.addAuthority(new SimpleGrantedAuthority("ROLE_JOATSE_ADMIN"));
		} else {
			user.removeAuthority(new SimpleGrantedAuthority("ROLE_JOATSE_ADMIN"));
		}
		userManager.updateUser(user);
		return Map.of("result", "User updated OK");
	}

	@DeleteMapping("/admin/users/{uuid}")
	@ResponseBody
	public Map<String, Object> deleteUser(@PathVariable(value="uuid") UUID uuid) {
		JoatseUser user = userManager.loadUserByUUID(uuid);
		if (user == null) {
			throw new IllegalArgumentException("User not found");
		}
		userManager.deleteUser(user);
		return Map.of("result", "User deleted OK");
	}
	
	@PostMapping("/admin/users/{uuid}/resetPassword")
	@ResponseBody
	public Map<String, Object> resetPassword(jakarta.servlet.http.HttpServletRequest request, @PathVariable(value="uuid") UUID uuid) throws InterruptedException, ExecutionException {
		JoatseUser user = userManager.loadUserByUUID(uuid);
		if (user == null) {
			throw new IllegalArgumentException("User not found");
		}
		Optional<String> email = user.getEmail();
		String baseUrl = request.getRequestURL().toString();
		if (email.isPresent() && userManager.isEmailEnabled()) {
			userManager.sendPasswordResetEmail(email.get(), baseUrl).get();
			return Map.of("result", "linkSent");
		} else {
			return Map.of("result", "gotSomeLink", "link", userManager.newPasswordResetLink(user, baseUrl));
		}
	}


}
