package com.peerlink.server.security;

import com.peerlink.server.entity.User;
import java.io.Serial;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * The principal stored in the HTTP session. It is a small, serializable snapshot rather than the
 * JPA entity, and the password hash is erased as soon as authentication succeeds.
 */
public final class AuthenticatedUser implements UserDetails, CredentialsContainer {

    @Serial
    private static final long serialVersionUID = 1L;

    public static final String ROLE_USER = "ROLE_USER";

    private final UUID id;
    private final String username;
    private final boolean enabled;
    private final List<GrantedAuthority> authorities;
    private String passwordHash;

    private AuthenticatedUser(UUID id, String username, String passwordHash, boolean enabled) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.enabled = enabled;
        this.authorities = List.of(new SimpleGrantedAuthority(ROLE_USER));
    }

    public static AuthenticatedUser from(User user) {
        return new AuthenticatedUser(user.getId(), user.getUsername(), user.getPasswordHash(), user.isEnabled());
    }

    public UUID getId() {
        return id;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public void eraseCredentials() {
        this.passwordHash = null;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AuthenticatedUser that && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "AuthenticatedUser{id=" + id + ", username='" + username + "', enabled=" + enabled + '}';
    }
}
