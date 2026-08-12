package com.allog.allogbe.routineverification.e2e;

import com.allog.allogbe.routineverification.policy.ChallengeVerificationPolicy;
import com.allog.allogbe.routineverification.policy.ChallengeVerificationPolicyProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * STAGE3 이후 실제 구현체가 없는 ChallengeVerificationPolicyProvider(연동 필요 지점)의
 * 통합테스트 전용 대역. 실제 Challenge 도메인이 생기기 전까지 E2E 테스트가 실제 Spring
 * 컨텍스트를 부팅하려면 이 빈이 반드시 필요하다.
 */
public class FakeChallengeVerificationPolicyProvider implements ChallengeVerificationPolicyProvider {

	private final Map<Long, ChallengeVerificationPolicy> policies = new ConcurrentHashMap<>();

	public void register(Long challengeId, ChallengeVerificationPolicy policy) {
		policies.put(challengeId, policy);
	}

	@Override
	public ChallengeVerificationPolicy getPolicy(Long challengeId) {
		ChallengeVerificationPolicy policy = policies.get(challengeId);
		if (policy == null) {
			throw new NoSuchElementException("테스트에 등록되지 않은 challengeId: " + challengeId);
		}
		return policy;
	}

	@TestConfiguration
	public static class Config {
		@Bean
		public FakeChallengeVerificationPolicyProvider fakeChallengeVerificationPolicyProvider() {
			return new FakeChallengeVerificationPolicyProvider();
		}
	}
}
