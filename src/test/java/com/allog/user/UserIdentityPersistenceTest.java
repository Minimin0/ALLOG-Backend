package com.allog.user;

import com.allog.user.domain.IdentityProvider;
import com.allog.user.domain.User;
import com.allog.user.domain.UserIdentity;
import com.allog.user.repository.UserIdentityRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class UserIdentityPersistenceTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private UserIdentityRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void appliesFlywayV4BeforeJpaValidation() {
        Integer migrations = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '4' AND success = TRUE",
                Integer.class
        );

        assertEquals(1, migrations);
    }

    @Test
    void persistsAndFindsIdentityByProviderAndSubject() {
        User user = User.create();
        entityManager.persist(user);
        UserIdentity identity = UserIdentity.local(user, "local-user-123", "hash");
        repository.saveAndFlush(identity);
        entityManager.clear();

        UserIdentity found = repository
                .findByProviderAndSubject(IdentityProvider.LOCAL, "local-user-123")
                .orElseThrow();

        assertEquals(identity.getId(), found.getId());
        assertEquals(user.getId(), found.getUser().getId());
        assertEquals(IdentityProvider.LOCAL, found.getProvider());
        assertEquals("local-user-123", found.getSubject());
        assertEquals("hash", found.getPasswordHash());
        assertNotNull(found.getCreatedAt());
        Timestamp storedCreatedAt = jdbcTemplate.queryForObject(
                "SELECT created_at FROM user_identity WHERE id = ?",
                Timestamp.class,
                found.getId()
        );
        assertEquals(found.getCreatedAt(), storedCreatedAt.toInstant());
    }

    @Test
    void rejectsDuplicateProviderAndSubject() {
        User firstUser = User.create();
        User secondUser = User.create();
        entityManager.persist(firstUser);
        entityManager.persist(secondUser);
        repository.saveAndFlush(UserIdentity.local(firstUser, "duplicate-local-user", "hash"));

        assertThrows(DataIntegrityViolationException.class, () -> repository.saveAndFlush(
                UserIdentity.local(secondUser, "duplicate-local-user", "hash")));
    }

    @Test
    void allowsOneUserToHaveMultipleIdentities() {
        User user = User.create();
        entityManager.persist(user);
        repository.save(UserIdentity.local(user, "local-user-one", "hash"));
        repository.save(UserIdentity.local(user, "local-user-two", "hash"));
        entityManager.flush();

        assertEquals(2, repository.count());
    }
}
