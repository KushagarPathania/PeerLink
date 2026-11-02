package com.peerlink.server.service;

import com.peerlink.server.dto.UserRegistrationRequest;
import com.peerlink.server.dto.UserResponse;
import com.peerlink.server.entity.User;
import com.peerlink.server.exception.AccountAlreadyExistsException;
import com.peerlink.server.exception.DataIntegrityViolations;
import com.peerlink.server.repository.UserRepository;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

@Service
@Validated
public class RegistrationService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public RegistrationService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Creates an account with a BCrypt-hashed password.
     *
     * @throws AccountAlreadyExistsException if the username or email is already registered
     */
    @Transactional
    public UserResponse register(@Valid UserRegistrationRequest request) {
        String username = User.normalize(request.getUsername());
        String email = User.normalize(request.getEmail());
        if (userRepository.existsByUsername(username)) {
            throw AccountAlreadyExistsException.usernameTaken();
        }
        if (userRepository.existsByEmail(email)) {
            throw AccountAlreadyExistsException.emailTaken();
        }

        User user = new User(username, email, passwordEncoder.encode(request.getPassword()));
        user.setDisplayName(request.getDisplayName());
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // A concurrent registration won the race between the checks above and the insert.
            throw translateUniqueViolation(e);
        }
        log.info("Registered user '{}'", user.getUsername());
        return UserResponse.from(user);
    }

    private static RuntimeException translateUniqueViolation(DataIntegrityViolationException e) {
        String constraint = DataIntegrityViolations.constraintName(e);
        if ("uk_users_username".equals(constraint)) {
            return AccountAlreadyExistsException.usernameTaken();
        }
        if ("uk_users_email".equals(constraint)) {
            return AccountAlreadyExistsException.emailTaken();
        }
        return e;
    }
}
