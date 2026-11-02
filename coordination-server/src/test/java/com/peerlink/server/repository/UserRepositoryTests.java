package com.peerlink.server.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.peerlink.server.entity.User;
import com.peerlink.server.support.RepositoryTestSupport;
import com.peerlink.server.support.TestFixtures;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

class UserRepositoryTests extends RepositoryTestSupport {

    @Autowired
    private UserRepository userRepository;

    @Test
    void findsUsersByNormalizedUsernameAndEmail() {
        User saved = userRepository.saveAndFlush(new User("  Alice ", "Alice@Example.COM", TestFixtures.PASSWORD_HASH));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(userRepository.findByUsername("alice")).contains(saved);
        assertThat(userRepository.findByEmail("alice@example.com")).contains(saved);
        assertThat(userRepository.findByUsernameOrEmail("nobody", "alice@example.com")).contains(saved);
        assertThat(userRepository.existsByUsername("alice")).isTrue();
        assertThat(userRepository.existsByEmail("bob@example.com")).isFalse();
    }

    @Test
    void rejectsDuplicateUsername() {
        userRepository.saveAndFlush(new User("carol", "carol@example.com", TestFixtures.PASSWORD_HASH));

        assertThatThrownBy(() -> userRepository.saveAndFlush(
                new User("carol", "carol2@example.com", TestFixtures.PASSWORD_HASH)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("uk_users_username");
    }

    @Test
    void rejectsDuplicateUsernameRegardlessOfCase() {
        userRepository.saveAndFlush(new User("dave", "dave@example.com", TestFixtures.PASSWORD_HASH));

        assertThatThrownBy(() -> userRepository.saveAndFlush(
                new User("DAVE", "dave2@example.com", TestFixtures.PASSWORD_HASH)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("uk_users_username");
    }

    @Test
    void rejectsDuplicateEmail() {
        userRepository.saveAndFlush(new User("erin", "shared@example.com", TestFixtures.PASSWORD_HASH));

        assertThatThrownBy(() -> userRepository.saveAndFlush(
                new User("frank", "SHARED@example.com", TestFixtures.PASSWORD_HASH)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("uk_users_email");
    }

    @Test
    void beanValidationRejectsInvalidUserBeforeReachingTheDatabase() {
        assertThatThrownBy(() -> userRepository.saveAndFlush(new User("x!", "not-an-email", " ")))
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void databaseRejectsUsernameThatIsNotNormalized() {
        assertSqlViolates("ck_users_username_format",
                "insert into users (username, email, password_hash) values ('Mixed', 'm@example.com', 'hash')");
    }

    @Test
    void databaseRejectsUpperCaseEmail() {
        assertSqlViolates("ck_users_email_format",
                "insert into users (username, email, password_hash) values ('mixed', 'M@Example.com', 'hash')");
    }

    @Test
    void databaseRejectsBlankPasswordHash() {
        assertSqlViolates("ck_users_password_hash_not_blank",
                "insert into users (username, email, password_hash) values ('blank', 'b@example.com', '  ')");
    }
}
