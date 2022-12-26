package org.aalku.joatse.cloud.service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.aalku.joatse.cloud.service.user.UserManager;
import org.aalku.joatse.cloud.service.user.vo.JoatseUser;
import org.aalku.joatse.cloud.tools.io.BandwithLimiter;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;

@Component
public class BandwithLimitManager implements InitializingBean {
	
	@Autowired
	private UserManager userManager;
	
	@Value("${cloud.bandwith.limit.global.bps:4096000}")
	private Long globalBandwithLimit;
	
	@Value("${cloud.bandwith.limit.pause.max.millis:500}")
	private long maxPauseMillis;

	private BandwithLimiter globalLimiter = new BandwithLimiter();
	
	private LoadingCache<UUID, BandwithLimiter> bandwithLimitPerUser = Caffeine.newBuilder().expireAfterAccess(5, TimeUnit.MINUTES).build(uuid -> {
		JoatseUser user = userManager.loadUserByUUID(uuid);
		BandwithLimiter bandwithLimiter = new BandwithLimiter();
		bandwithLimiter.setMaxPauseMillis(maxPauseMillis);
		bandwithLimiter.setParent(globalLimiter);
		bandwithLimiter.setLimitBps(user.getBandwithLimit().orElse(null));
		return bandwithLimiter;
	});

	@Override
	public void afterPropertiesSet() throws Exception {
		this.globalLimiter.setMaxPauseMillis(maxPauseMillis);
		this.globalLimiter.setLimitBps(globalBandwithLimit);
	}

	public Long getGlobalBandwithLimit() {
		return globalBandwithLimit;
	}

	public void setGlobalBandwithLimit(Long globalBandwithLimit) {
		this.globalBandwithLimit = globalBandwithLimit;
		this.globalLimiter.setLimitBps(globalBandwithLimit);
	}
	
	public BandwithLimiter getUserBandwithLimiter(UUID userUuid) {
		return bandwithLimitPerUser.get(userUuid);
	}

	public BandwithLimiter getGlobalBandwithLimiter() {
		return globalLimiter;
	}
}
