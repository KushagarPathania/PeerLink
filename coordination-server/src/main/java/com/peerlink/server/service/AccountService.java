package com.peerlink.server.service;

import com.peerlink.server.dto.UserResponse;
import com.peerlink.server.exception.AccountNotFoundException;
import com.peerlink.server.repository.UserRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Account reads for the signed-in user. Callers pass the id from the server-side security
 * context, never an id taken from the request.
 */
@Service
public class AccountService {

    private final UserRepository userRepository;

    public AccountService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public UserResponse getProfile(UUID userId) {
        return userRepository.findById(userId)
                .map(UserResponse::from)
                .orElseThrow(AccountNotFoundException::new);
    }
}
