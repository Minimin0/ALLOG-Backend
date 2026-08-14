package com.allog.allogbe.routineverification.e2e;

import com.allog.allogbe.routineverification.domain.VisionAnalysisResult;
import com.allog.allogbe.routineverification.vision.VisionAnalysisAttemptException;
import com.allog.allogbe.routineverification.vision.VisionAnalysisClient;
import com.allog.allogbe.routineverification.vision.VisionAnalysisRequest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * 실제 ClaudeVisionAnalysisClient(네트워크/API 키 필요) 대신 사용하는 통합테스트 전용 대역.
 * @Primary 로 실제 빈보다 우선 적용된다. 시나리오별로 응답을 미리 설정하거나 실패를 시뮬레이션한다.
 */
public class FakeVisionAnalysisClient implements VisionAnalysisClient {

	private final AtomicReference<Function<VisionAnalysisRequest, VisionAnalysisResult>> behavior =
			new AtomicReference<>(req -> {
				throw new IllegalStateException("이 테스트를 위한 Vision 응답이 설정되지 않았습니다.");
			});
	private final AtomicInteger callCount = new AtomicInteger();

	public void respondWith(VisionAnalysisResult result) {
		behavior.set(req -> result);
	}

	public void alwaysFail() {
		behavior.set(req -> {
			throw new VisionAnalysisAttemptException("테스트 시뮬레이션: Vision API 실패");
		});
	}

	public int callCount() {
		return callCount.get();
	}

	public void reset() {
		callCount.set(0);
	}

	@Override
	public VisionAnalysisResult analyze(VisionAnalysisRequest request) {
		callCount.incrementAndGet();
		return behavior.get().apply(request);
	}

	@TestConfiguration
	public static class Config {
		@Bean
		@Primary
		public FakeVisionAnalysisClient fakeVisionAnalysisClient() {
			return new FakeVisionAnalysisClient();
		}
	}
}
