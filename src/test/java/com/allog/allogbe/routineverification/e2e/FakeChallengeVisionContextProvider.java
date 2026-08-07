package com.allog.allogbe.routineverification.e2e;

import com.allog.allogbe.routineverification.vision.ChallengeVisionContext;
import com.allog.allogbe.routineverification.vision.ChallengeVisionContextProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;

/** STAGE8 ChallengeVisionContextProvider(연동 필요 지점)의 통합테스트 전용 대역. */
public class FakeChallengeVisionContextProvider implements ChallengeVisionContextProvider {

	private final Map<Long, ChallengeVisionContext> contexts = new ConcurrentHashMap<>();

	public void register(Long challengeId, ChallengeVisionContext context) {
		contexts.put(challengeId, context);
	}

	@Override
	public ChallengeVisionContext getContext(Long challengeId) {
		ChallengeVisionContext context = contexts.get(challengeId);
		if (context == null) {
			throw new NoSuchElementException("테스트에 등록되지 않은 challengeId: " + challengeId);
		}
		return context;
	}

	@TestConfiguration
	public static class Config {
		@Bean
		public FakeChallengeVisionContextProvider fakeChallengeVisionContextProvider() {
			return new FakeChallengeVisionContextProvider();
		}
	}
}
