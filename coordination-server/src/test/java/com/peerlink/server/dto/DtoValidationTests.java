package com.peerlink.server.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class DtoValidationTests {

    private static final String CHECKSUM = "A".repeat(64);

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    private static final String PASSWORD = "correct-horse-battery";

    @Test
    void acceptsValidRegistration() {
        assertThat(violatedFields(new UserRegistrationRequest("Alice_01", "alice@example.com", "Alice", PASSWORD, PASSWORD)))
                .isEmpty();
    }

    @Test
    void rejectsInvalidRegistration() {
        assertThat(violatedFields(new UserRegistrationRequest("a b", "nope", "x".repeat(101), "short", "short")))
                .containsExactlyInAnyOrder("username", "email", "displayName", "password");
        assertThat(violatedFields(new UserRegistrationRequest(" ", " ", " ", " ", " ")))
                .containsExactlyInAnyOrder("username", "email", "displayName", "password", "confirmPassword");
    }

    @Test
    void requiresAtLeastTwelveCharacterPassword() {
        String elevenChars = "abcdefghijk";
        assertThat(violatedFields(new UserRegistrationRequest("alice", "alice@example.com", "Alice", elevenChars, elevenChars)))
                .containsExactly("password");
    }

    @Test
    void rejectsPasswordLongerThanBcryptLimitInBytes() {
        // 40 characters but 80 bytes in UTF-8: BCrypt would reject or silently truncate it.
        String multiByte = "é".repeat(40);
        assertThat(violatedFields(new UserRegistrationRequest("alice", "alice@example.com", "Alice", multiByte, multiByte)))
                .containsExactly("password");
    }

    @Test
    void reportsPasswordMismatchOnConfirmationField() {
        assertThat(violatedFields(new UserRegistrationRequest("alice", "alice@example.com", "Alice", PASSWORD, PASSWORD + "x")))
                .containsExactly("confirmPassword");
    }

    @Test
    void registrationToStringMasksPassword() {
        String text = new UserRegistrationRequest("alice", "alice@example.com", "Alice", PASSWORD, PASSWORD).toString();

        assertThat(text).doesNotContain(PASSWORD).contains("****");
    }

    @Test
    void loginRequestRequiresBothFieldsAndMasksPassword() {
        assertThat(violatedFields(new LoginRequest(" ", ""))).containsExactlyInAnyOrder("identifier", "password");
        assertThat(new LoginRequest("alice", PASSWORD).toString()).doesNotContain(PASSWORD);
    }

    @Test
    void peerRegistrationValidatesPort() {
        assertThat(violatedFields(new PeerRegistrationRequest("laptop", 9090))).isEmpty();
        assertThat(violatedFields(new PeerRegistrationRequest("laptop", 70000))).containsExactly("listeningPort");
        assertThat(violatedFields(new PeerRegistrationRequest(" ", null)))
                .containsExactlyInAnyOrder("deviceName", "listeningPort");
    }

    @Test
    void sharedFileRegistrationValidatesMetadata() {
        assertThat(violatedFields(new SharedFileRegistrationRequest("a.txt", 0L, CHECKSUM, null, 65536))).isEmpty();
        assertThat(violatedFields(new SharedFileRegistrationRequest("../a.txt", -1L, "xyz", "text/plain\n", 0)))
                .containsExactlyInAnyOrder("fileName", "fileSizeBytes", "sha256Checksum", "contentType", "chunkSizeBytes");
    }

    @Test
    void sharedFileChunkSizeMustBeWithinTheTransferProtocolLimits() {
        for (int supported : new int[] {4096, 65536}) {
            assertThat(violatedFields(new SharedFileRegistrationRequest("a.txt", 1L, CHECKSUM, null, supported))).isEmpty();
        }
        // 8 and 16 bytes used to stall transfers; 1 MiB never fitted a data frame.
        for (int unsupported : new int[] {1, 8, 16, 4095, 65537, 1024 * 1024}) {
            assertThat(violatedFields(new SharedFileRegistrationRequest("a.txt", 1L, CHECKSUM, null, unsupported)))
                    .as("chunk size %d", unsupported).containsExactly("chunkSizeBytes");
        }
    }

    @Test
    void permissionExpiryMustBeInTheFuture() {
        assertThat(violatedFields(new SharePermissionRequest("bob", null))).isEmpty();
        assertThat(violatedFields(new SharePermissionRequest("bob", Instant.now().minusSeconds(1))))
                .containsExactly("expiresAt");
    }

    @Test
    void transferRequestRequiresIds() {
        assertThat(violatedFields(new TransferCreateRequest(null, null)))
                .containsExactlyInAnyOrder("sharedFileId", "receiverPeerId");
    }

    private static Set<String> violatedFields(Object dto) {
        return validator.validate(dto).stream().map(v -> v.getPropertyPath().toString()).collect(Collectors.toSet());
    }
}
