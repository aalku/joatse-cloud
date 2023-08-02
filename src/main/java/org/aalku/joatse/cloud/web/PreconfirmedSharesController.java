package org.aalku.joatse.cloud.web;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.aalku.joatse.cloud.service.sharing.shared.SharedResourceLot;
import org.aalku.joatse.cloud.service.user.UserManager;
import org.aalku.joatse.cloud.service.user.repository.PreconfirmedSharesRepository;
import org.aalku.joatse.cloud.service.user.vo.PreconfirmedShare;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PreconfirmedSharesController {
	
	@Autowired
	private UserManager userManager;

	@Autowired
	private PreconfirmedSharesRepository repository;

	@Autowired
	private SessionController sessionController;

	@PutMapping("/preconfirmedShares")
	@ResponseBody
	public Map<String, Object> preconfirm(@RequestBody Map<String, Object> payload) {
		UUID uuid = Optional.ofNullable((String) payload.get("session")).map(u->UUID.fromString(u)).get();
		Optional<SharedResourceLot> sharedResourceLotOptional = sessionController.getSession(uuid);
		if (sharedResourceLotOptional.isPresent()) {
			Collection<PreconfirmedShare> lots = repository.findByOwner(userManager.getAuthenticatedUser().orElseThrow());
			PreconfirmedShare vo = PreconfirmedShare.fromSharedResourceLot(sharedResourceLotOptional.get());
			// Delete others that are equal
			lots.stream().filter(l->l.getResources().equals(vo.getResources())).forEach(l->{
				repository.delete(l);
			});
			repository.save(vo);
			return Map.of("result", "Preconfirmed OK");
		} else {
			throw new IllegalArgumentException("SharedLot not found");
		}
	}
	
	@DeleteMapping("/preconfirmedShares")
	@ResponseBody
	public Map<String, Object> deletePreconfirm(@RequestBody Map<String, Object> payload) {
		UUID uuid = Optional.ofNullable((String) payload.get("preconfirmed")).map(u->UUID.fromString(u)).get();
		Optional<PreconfirmedShare> lot = repository.findById(uuid)
				.filter(x -> x.getOwner().equals(userManager.getAuthenticatedUser().orElseThrow()));
		if (lot.isPresent()) {
			repository.delete(lot.get());
			return Map.of("result", "Delete preconfirm OK");
		} else {
			throw new IllegalArgumentException("SharedLot not found");
		}
	}

	@GetMapping("/preconfirmedShares")
	@ResponseBody
	public String getPreconfirmedShares(@RequestParam(name="session", required = false) UUID sessionUuid) {
		JSONObject res = new JSONObject();
		Predicate<PreconfirmedShare> filter = Optional.ofNullable(sessionUuid).flatMap(u -> sessionController.getSession(u))
				.map(s -> s.toJsonSharedResources()).map(j -> j.toString(0))
				.map(j -> (Predicate<PreconfirmedShare>) (x -> x.getResources().equals(j)))
				.orElse((Predicate<PreconfirmedShare>) x -> true);
		Collection<PreconfirmedShare> lots = repository.findByOwner(userManager.getAuthenticatedUser().orElseThrow());
		res.put("preconfirmedShares", new JSONArray(lots.stream().filter(filter)
				.map(l -> new JSONObject(l.getResources()).put("uuid", l.getUuid())).collect(Collectors.toList())));
		return res.toString(0);
	}

}
