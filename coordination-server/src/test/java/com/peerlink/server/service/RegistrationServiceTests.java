package com.peerlink.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.peerlink.server.dto.UserRegistrationRequest;
import com.peerlink.server.entity.User;
import com.peerlink.server.exception.AccountAlreadyExistsException;
import com.peerlink.server.exception.ApiErrorCode;
import com.peerlink.server.repository.UserRepository;
import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Covers paths that are hard to trigger against a real database, such as two registrations
 * racing past the existence checks. Happy paths are tested end-to-end in AuthApiIntegrationTests.
 */
class RegistrationServiceTests {

    private static final String PASSWORD = "correct-horse-battery";

    private final UserRepository userRepository = mock(UserRepository.class);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
    private final RegistrationService service = new RegistrationService(userRepository, passwordEncoder);

    @Test
    void storesNormalizedUserWithHashedPassword() {
        service.register(request("Alice", "Alice@Example.com"));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getUsername()).isEqualTo("alice");
        assertThat(saved.getValue().getEmail()).isEqualTo("alice@example.com");
        assertThat(saved.getValue().getPasswordHash()).isNotEqualTo(PASSWORD);
        assertThat(passwordEncoder.matches(PASSWORD, saved.getValue().getPasswordHash())).isTrue();
    }

    @Test
    void rejectsExistingUsernameBeforeHashing() {
        given(userRepository.existsByUsername("alice")).willReturn(true);

        assertThatThrownBy(() -> service.register(request("ALICE", "a@example.com")))
                .isInstanceOfSatisfying(AccountAlreadyExistsException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ApiErrorCode.USERNAME_TAKEN));
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void translatesConcurrentEmailConflictIntoFriendlyError() {
        given(userRepository.saveAndFlush(any())).willThrow(uniqueViolation("uk_users_email"));

        assertThatThrownBy(() -> service.register(request("alice", "a@example.com")))
                .isInstanceOfSatisfying(AccountAlreadyExistsException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ApiErrorCode.EMAIL_TAKEN);
                    assertThat(e.getField()).isEqualTo("email");
                });
    }

    @Test
    void translatesConcurrentUsernameConflictIntoFriendlyError() {
        given(userRepository.saveAndFlush(any())).willThrow(uniqueViolation("uk_users_username"));

        assertThatThrownBy(() -> service.register(request("alice", "a@example.com")))
                .isInstanceOfSatisfying(AccountAlreadyExistsException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ApiErrorCode.USERNAME_TAKEN));
    }

    @Test
    void rethrowsUnrelatedIntegrityErrors() {
        DataIntegrityViolationException other = uniqueViolation("ck_something_else");
        given(userRepository.saveAndFlush(any())).willThrow(other);

        assertThatThrownBy(() -> service.register(request("alice", "a@example.com"))).isSameAs(other);
    }

    private static DataIntegrityViolationException uniqueViolation(String constraint) {
        return new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("duplicate key", new SQLException("duplicate key"), constraint));
    }

    private static UserRegistrationRequest request(String username, String email) {
        return new UserRegistrationRequest(username, email, "Alice", PASSWORD, PASSWORD);
    }
}
