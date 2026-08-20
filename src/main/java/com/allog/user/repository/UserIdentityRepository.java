package com.allog.user.repository;

import com.allog.user.domain.IdentityProvider;
import com.allog.user.domain.UserIdentity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserIdentityRepository extends JpaRepository<UserIdentity, Long> {

    Optional<UserIdentity> findByProviderAndSubject(IdentityProvider provider, String subject);

    boolean existsByProviderAndSubject(IdentityProvider provider, String subject);
}
