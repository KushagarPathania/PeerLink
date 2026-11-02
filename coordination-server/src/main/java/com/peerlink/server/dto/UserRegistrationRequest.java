package com.peerlink.server.dto;

import com.peerlink.server.entity.User;
import com.peerlink.server.validation.MaxUtf8Bytes;
import com.peerlink.server.validation.PasswordConfirmation;
import com.peerlink.server.validation.PasswordsMatch;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Sign-up data, used both as the JSON body of {@code POST /api/auth/register} and as the
 * Thymeleaf registration form (hence a JavaBean rather than a record). The plaintext password
 * exists only in this object and is hashed before anything is stored.
 */
@PasswordsMatch
public class UserRegistrationRequest implements PasswordConfirmation {

    public static final int MIN_PASSWORD_LENGTH = 12;
    /** BCrypt only uses the first 72 bytes of a password; Spring Security rejects longer ones. */
    public static final int MAX_PASSWORD_BYTES = 72;

    @NotBlank
    @Size(min = 3, max = User.USERNAME_MAX_LENGTH)
    @Pattern(regexp = "^[A-Za-z0-9._-]+$", message = "may contain only letters, digits, '.', '_' and '-'")
    private String username;

    @NotBlank
    @Email
    @Size(max = User.EMAIL_MAX_LENGTH)
    private String email;

    @NotBlank
    @Size(max = User.DISPLAY_NAME_MAX_LENGTH)
    private String displayName;

    @NotBlank
    @Size(min = MIN_PASSWORD_LENGTH, max = MAX_PASSWORD_BYTES)
    @MaxUtf8Bytes(value = MAX_PASSWORD_BYTES, message = "must be at most 72 bytes when UTF-8 encoded")
    private String password;

    @NotBlank
    private String confirmPassword;

    public UserRegistrationRequest() {
    }

    public UserRegistrationRequest(String username, String email, String displayName, String password,
                                   String confirmPassword) {
        this.username = username;
        this.email = email;
        this.displayName = displayName;
        this.password = password;
        this.confirmPassword = confirmPassword;
    }

    /** Drops the passwords so a re-rendered form never echoes them back to the browser. */
    public void clearPasswords() {
        this.password = null;
        this.confirmPassword = null;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    @Override
    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    @Override
    public String getConfirmPassword() {
        return confirmPassword;
    }

    public void setConfirmPassword(String confirmPassword) {
        this.confirmPassword = confirmPassword;
    }

    @Override
    public String toString() {
        return "UserRegistrationRequest[username=" + username + ", email=" + email + ", password=****]";
    }
}
