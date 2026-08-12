package com.allog.allogbe.routineverification.service;

import com.allog.allogbe.routineverification.domain.MetadataCheck;
import com.allog.allogbe.routineverification.domain.SubmissionType;
import com.allog.allogbe.routineverification.dto.RoutineVerificationSubmitRequest;
import com.allog.allogbe.routineverification.exception.DisallowedSubmissionTypeException;
import com.allog.allogbe.routineverification.exception.OutsideVerificationTimeWindowException;
import com.allog.allogbe.routineverification.policy.ChallengeVerificationPolicy;
import com.allog.allogbe.routineverification.policy.ChallengeVerificationPolicyProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoutineVerificationSubmissionGateTest {

	private static final Long CHALLENGE_ID = 1L;
	private static final LocalDate DAY = LocalDate.of(2026, 8, 8);

	@Mock
	private ChallengeVerificationPolicyProvider policyProvider;

	private RoutineVerificationSubmissionGate gate;

	@BeforeEach
	void setUp() {
		gate = new RoutineVerificationSubmissionGate(policyProvider);
	}

	private void givenPolicy(ChallengeVerificationPolicy policy) {
		when(policyProvider.getPolicy(anyLong())).thenReturn(policy);
	}

	private RoutineVerificationSubmitRequest requestAt(SubmissionType type, LocalTime time) {
		return new RoutineVerificationSubmitRequest(
				100L, CHALLENGE_ID, 200L, type, "https://cdn.allog.dev/media/x.jpg", DAY.atTime(time));
	}

	@Test
	void 허용된_타입이고_시간_범위_내이면_통과하고_withinTimeWindow가_true다() {
		givenPolicy(new ChallengeVerificationPolicy(
				Set.of(SubmissionType.PHOTO), LocalTime.of(6, 0), LocalTime.of(10, 0)));

		MetadataCheck result = gate.validate(requestAt(SubmissionType.PHOTO, LocalTime.of(8, 0)));

		assertThat(result.isWithinTimeWindow()).isTrue();
		assertThat(result.isDuplicate()).isFalse();
		assertThat(result.getDuplicateOfId()).isNull();
	}

	@Test
	void 허용되지_않은_제출타입이면_DisallowedSubmissionTypeException() {
		givenPolicy(new ChallengeVerificationPolicy(
				Set.of(SubmissionType.PHOTO), LocalTime.of(6, 0), LocalTime.of(10, 0)));

		assertThatThrownBy(() -> gate.validate(requestAt(SubmissionType.VIDEO, LocalTime.of(8, 0))))
				.isInstanceOf(DisallowedSubmissionTypeException.class);
	}

	@Test
	void 허용된_타입이지만_시간_범위_밖이면_OutsideVerificationTimeWindowException() {
		givenPolicy(new ChallengeVerificationPolicy(
				Set.of(SubmissionType.PHOTO), LocalTime.of(6, 0), LocalTime.of(10, 0)));

		assertThatThrownBy(() -> gate.validate(requestAt(SubmissionType.PHOTO, LocalTime.of(11, 0))))
				.isInstanceOf(OutsideVerificationTimeWindowException.class);
	}

	@Test
	void 타입도_비허용이고_시간도_밖이면_타입_검증이_먼저_실패한다() {
		givenPolicy(new ChallengeVerificationPolicy(
				Set.of(SubmissionType.PHOTO), LocalTime.of(6, 0), LocalTime.of(10, 0)));

		assertThatThrownBy(() -> gate.validate(requestAt(SubmissionType.VIDEO, LocalTime.of(23, 0))))
				.isInstanceOf(DisallowedSubmissionTypeException.class);
	}

	@Test
	void 시작_경계값은_포함된다() {
		givenPolicy(new ChallengeVerificationPolicy(
				Set.of(SubmissionType.APP_RECORD), LocalTime.of(6, 0), LocalTime.of(10, 0)));

		MetadataCheck result = gate.validate(requestAt(SubmissionType.APP_RECORD, LocalTime.of(6, 0)));

		assertThat(result.isWithinTimeWindow()).isTrue();
	}

	@Test
	void 종료_경계값은_포함된다() {
		givenPolicy(new ChallengeVerificationPolicy(
				Set.of(SubmissionType.APP_RECORD), LocalTime.of(6, 0), LocalTime.of(10, 0)));

		MetadataCheck result = gate.validate(requestAt(SubmissionType.APP_RECORD, LocalTime.of(10, 0)));

		assertThat(result.isWithinTimeWindow()).isTrue();
	}

	@Test
	void 자정을_넘기는_윈도우에서_자정_이후_시간도_허용된다() {
		// 수면 챌린지: 22:00 ~ 02:00
		givenPolicy(new ChallengeVerificationPolicy(
				Set.of(SubmissionType.PHOTO), LocalTime.of(22, 0), LocalTime.of(2, 0)));

		MetadataCheck result = gate.validate(requestAt(SubmissionType.PHOTO, LocalTime.of(1, 30)));

		assertThat(result.isWithinTimeWindow()).isTrue();
	}

	@Test
	void 자정을_넘기는_윈도우에서_범위_밖_시간은_거부된다() {
		givenPolicy(new ChallengeVerificationPolicy(
				Set.of(SubmissionType.PHOTO), LocalTime.of(22, 0), LocalTime.of(2, 0)));

		assertThatThrownBy(() -> gate.validate(requestAt(SubmissionType.PHOTO, LocalTime.of(12, 0))))
				.isInstanceOf(OutsideVerificationTimeWindowException.class);
	}
}
