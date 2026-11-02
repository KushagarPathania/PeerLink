package com.peerlink.server.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.peerlink.server.entity.User;
import com.peerlink.server.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

class AuthenticatedUserTests {

    private static final String HASH = "$2a$10$abcdefghijklmnopqrstuuSomeHashValueForTestsOnly1234567";

    private final UserRepository userRepository = mock(UserRepository.class);
    private final PeerLinkUserDetailsService service = new PeerLinkUserDetailsService(userRepository);

    @Test
    void loadsByNormalizedIdentifierAndMapsStatusAndRole() {
        User user = new User("alice", "alice@example.com", HASH);
        user.disable();
        given(userRepository.findByUsernameOrEmail("alice@example.com", "alice@example.com")).willReturn(Optional.of(user));

        UserDetails details = service.loadUserByUsername("  Alice@Example.com ");

        assertThat(details.getUsername()).isEqualTo("alice");
        assertThat(details.getPassword()).isEqualTo(HASH);
        assertThat(details.isEnabled()).isFalse();
        assertThat(details.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_USER");
    }

    @Test
    void unknownIdentifierThrowsUsernameNotFound() {
        given(userRepository.findByUsernameOrEmail("ghost", "ghost")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadUserByUsername("ghost")).isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void erasesHashAndNeverPrintsIt() {
        AuthenticatedUser principal = AuthenticatedUser.from(new User("alice", "alice@example.com", HASH));

        assertThat(principal.toString()).doesNotContain(HASH);
        principal.eraseCredentials();
        assertThat(principal.getPassword()).isNull();
    }
}
