package com.allog.user.repository;

import com.allog.user.domain.UserOnboarding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserOnboardingRepository extends JpaRepository<UserOnboarding, Long> {

    Optional<UserOnboarding> findByUser_Id(Long userId);
}
