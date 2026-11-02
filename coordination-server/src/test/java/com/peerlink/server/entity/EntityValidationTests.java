package com.peerlink.server.entity;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.net.InetAddress;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EntityValidationTests {

    private static jakarta.validation.ValidatorFactory factory;
    private static Validator validator;

    private final User owner = new User("owner", "owner@example.com", "hash");
    private final Peer peer = new Peer(owner, "laptop", InetAddress.getLoopbackAddress(), 9090);

    @BeforeAll
    static void createValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @Test
    void validEntitiesPass() {
        assertThat(validator.validate(owner)).isEmpty();
        assertThat(validator.validate(peer)).isEmpty();
        assertThat(validator.validate(new SharedFile(peer, "report.pdf", 0, "a".repeat(64), 1024))).isEmpty();
    }

    @Test
    void userRequiresValidUsernameEmailAndHash() {
        assertThat(violatedFields(new User("ab", "owner@example.com", "hash"))).containsExactly("username");
        assertThat(violatedFields(new User("bad name!", "owner@example.com", "hash"))).containsExactly("username");
        assertThat(violatedFields(new User("owner", "not-an-email", "hash"))).containsExactly("email");
        assertThat(violatedFields(new User("owner", "owner@example.com", " "))).containsExactly("passwordHash");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 65536})
    void peerRejectsInvalidPorts(int port) {
        assertThat(violatedFields(new Peer(owner, "laptop", InetAddress.getLoopbackAddress(), port)))
                .containsExactly("listeningPort");
    }

    @Test
    void peerRequiresDeviceNameButNotAnAddressBeforeFirstHeartbeat() {
        assertThat(violatedFields(new Peer(owner, " ", 9090))).containsExactly("deviceName");
        assertThat(violatedFields(new Peer(owner, "laptop", 9090))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"../secret.txt", "dir/file", "C:\\file", "..", ".", "tab\tname"})
    void sharedFileRejectsPathsAsFileNames(String fileName) {
        assertThat(violatedFields(new SharedFile(peer, fileName, 1, "a".repeat(64), 1024))).containsExactly("fileName");
    }

    @Test
    void sharedFileRejectsMalformedChecksum() {
        assertThat(violatedFields(new SharedFile(peer, "a.txt", 1, "xyz", 1024))).containsExactly("sha256Checksum");
        assertThat(violatedFields(new SharedFile(peer, "a.txt", 1, "a".repeat(63), 1024))).containsExactly("sha256Checksum");
    }

    private static Set<String> violatedFields(Object entity) {
        Set<ConstraintViolation<Object>> violations = validator.validate(entity);
        return violations.stream().map(v -> v.getPropertyPath().toString())
                .collect(java.util.stream.Collectors.toSet());
    }
}
