package com.peerlink.server.security;

import com.peerlink.server.entity.User;
import com.peerlink.server.repository.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads accounts from PostgreSQL by username or email. The two can never collide: usernames
 * may not contain '@' and emails must.
 */
@Service
public class PeerLinkUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public PeerLinkUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String identifier) {
        String normalized = User.normalize(identifier);
        return userRepository.findByUsernameOrEmail(normalized, normalized)
                .map(AuthenticatedUser::from)
                // Never shown to clients: the provider converts this into a generic BadCredentialsException.
                .orElseThrow(() -> new UsernameNotFoundException("No account matches the given identifier"));
    }
}
