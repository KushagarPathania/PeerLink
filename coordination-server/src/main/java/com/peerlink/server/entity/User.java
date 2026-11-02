package com.peerlink.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Locale;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * A registered account. Only a password hash is ever stored; this entity is never serialized
 * to clients directly (see {@code UserResponse}).
 */
@Entity
@Table(name = "users")
public class User extends BaseEntity {

    public static final String USERNAME_PATTERN = "^[a-z0-9._-]{3,32}$";
    public static final int USERNAME_MAX_LENGTH = 32;
    public static final int EMAIL_MAX_LENGTH = 254;
    public static final int DISPLAY_NAME_MAX_LENGTH = 100;

    @NotBlank
    @Pattern(regexp = USERNAME_PATTERN)
    @Column(name = "username", nullable = false, length = USERNAME_MAX_LENGTH)
    private String username;

    @NotBlank
    @Email
    @Size(max = EMAIL_MAX_LENGTH)
    @Column(name = "email", nullable = false, length = EMAIL_MAX_LENGTH)
    private String email;

    @NotBlank
    @Size(max = 255)
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Size(max = DISPLAY_NAME_MAX_LENGTH)
    @Column(name = "display_name", length = DISPLAY_NAME_MAX_LENGTH)
    private String displayName;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected User() {
    }

    public User(String username, String email, String passwordHash) {
        this.username = normalize(username);
        this.email = normalize(email);
        this.passwordHash = passwordHash;
    }

    /** Usernames and emails are stored lower-case so uniqueness cannot be bypassed by case. */
    public static String normalize(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    public String getUsername() {
        return username;
    }

    public String getEmail() {
        return email;
    }

    public void changeEmail(String email) {
        this.email = normalize(email);
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void changePasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName == null || displayName.isBlank() ? null : displayName.trim();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void enable() {
        this.enabled = true;
    }

    public void disable() {
        this.enabled = false;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public String toString() {
        return "User{id=" + getId() + ", username='" + username + "', enabled=" + enabled + '}';
    }
}
