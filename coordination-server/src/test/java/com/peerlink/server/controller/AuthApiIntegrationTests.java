package com.peerlink.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.peerlink.server.entity.User;
import com.peerlink.server.support.WebIntegrationTestSupport;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

class AuthApiIntegrationTests extends WebIntegrationTestSupport {

    // --- Registration ---------------------------------------------------------------

    @Test
    void registersAccountWithBcryptHashAndNoPasswordInResponse() throws Exception {
        String username = uniqueUsername();

        MvcResult result = register(Map.of(
                "username", username.toUpperCase(), "email", username + "@Example.com",
                "displayName", "New User", "password", PASSWORD, "confirmPassword", PASSWORD))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.email").value(username + "@example.com"))
                .andExpect(jsonPath("$.displayName").value("New User"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andReturn();

        User stored = userRepository.findByUsername(username).orElseThrow();
        assertThat(stored.getPasswordHash()).isNotEqualTo(PASSWORD).startsWith("$2");
        assertThat(passwordEncoder.matches(PASSWORD, stored.getPasswordHash())).isTrue();
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContainIgnoringCase("password").doesNotContain(stored.getPasswordHash());
    }

    @Test
    void rejectsDuplicateUsernameIgnoringCase() throws Exception {
        String username = uniqueUsername();
        createUser(username);

        register(validRegistration(username.toUpperCase(), "other-" + username + "@example.com"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USERNAME_TAKEN"))
                .andExpect(jsonPath("$.errors[0].field").value("username"))
                .andExpect(jsonPath("$.detail").value("That username is already taken."));
    }

    @Test
    void rejectsDuplicateEmailWithoutLeakingDatabaseDetails() throws Exception {
        String existing = uniqueUsername();
        createUser(existing);

        MvcResult result = register(validRegistration(uniqueUsername(), existing + "@example.com"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_TAKEN"))
                .andExpect(jsonPath("$.errors[0].field").value("email"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("uk_users").doesNotContainIgnoringCase("constraint").doesNotContainIgnoringCase("sql");
    }

    @Test
    void rejectsPasswordMismatch() throws Exception {
        String username = uniqueUsername();
        Map<String, String> body = Map.of("username", username, "email", username + "@example.com",
                "displayName", "X", "password", PASSWORD, "confirmPassword", PASSWORD + "!");

        register(body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("confirmPassword"));
        assertThat(userRepository.existsByUsername(username)).isFalse();
    }

    @Test
    void rejectsInvalidEmailAndShortPasswordWithoutEchoingValues() throws Exception {
        MvcResult result = register(Map.of("username", uniqueUsername(), "email", "not-an-email",
                "displayName", "X", "password", "short", "confirmPassword", "short"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(org.hamcrest.Matchers.containsInAnyOrder("email", "password")))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("not-an-email").doesNotContain("\"short\"");
    }

    @Test
    void ignoresClientSuppliedIdAndEnabledFlags() throws Exception {
        String username = uniqueUsername();
        Map<String, Object> body = new java.util.HashMap<>(validRegistration(username, username + "@example.com"));
        body.put("id", "00000000-0000-0000-0000-000000000001");
        body.put("enabled", false);

        register(body).andExpect(status().isCreated()).andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.not("00000000-0000-0000-0000-000000000001")));
    }

    @Test
    void registrationRequiresCsrfToken() throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json(validRegistration(uniqueUsername(), "x@example.com"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
    }

    // --- Login ----------------------------------------------------------------------

    @Test
    void logsInWithUsernameOrEmailAndRotatesSessionId() throws Exception {
        String username = uniqueUsername();
        createUser(username);
        MockHttpSession session = new MockHttpSession();
        String idBeforeLogin = session.getId();

        MvcResult result = login(session, username.toUpperCase(), PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(username))
                .andReturn();

        assertThat(session.getId()).isNotEqualTo(idBeforeLogin);
        // The body is the public profile only: no session id, token, password or hash fields.
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("id", "username", "email", "displayName", "enabled", "createdAt");

        login(new MockHttpSession(), username + "@EXAMPLE.com", PASSWORD).andExpect(status().isOk());
    }

    @Test
    void wrongPasswordAndUnknownUserGetIdenticalGenericError() throws Exception {
        String username = uniqueUsername();
        createUser(username);

        String wrongPassword = login(new MockHttpSession(), username, "wrong-password-123")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andReturn().getResponse().getContentAsString();
        String unknownUser = login(new MockHttpSession(), "nobody-" + username, PASSWORD)
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(unknownUser).get("detail"))
                .isEqualTo(objectMapper.readTree(wrongPassword).get("detail"));
    }

    @Test
    void disabledAccountCannotAuthenticateAndIsOnlyRevealedWithCorrectPassword() throws Exception {
        String username = uniqueUsername();
        User user = createUser(username);
        user.disable();
        userRepository.saveAndFlush(user);
        MockHttpSession session = new MockHttpSession();

        login(session, username, PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
        login(new MockHttpSession(), username, "wrong-password-123")
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        mockMvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
    }

    // --- Current user, logout and CSRF ------------------------------------------------

    @Test
    void meRequiresAuthenticationAndNeverRedirects() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void meReturnsOnlyTheSessionUserRegardlessOfSuppliedIds() throws Exception {
        String alice = uniqueUsername();
        createUser(alice);
        User bob = createUser(uniqueUsername());
        MockHttpSession session = apiLogin(alice);

        mockMvc.perform(get("/api/auth/me").session(session)
                        .param("userId", bob.getId().toString())
                        .param("id", bob.getId().toString())
                        .param("username", bob.getUsername()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(alice))
                .andExpect(jsonPath("$.email").value(alice + "@example.com"));
    }

    @Test
    void logoutInvalidatesSessionSoItCannotBeReused() throws Exception {
        String username = uniqueUsername();
        createUser(username);
        MockHttpSession session = apiLogin(username);
        mockMvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/logout").session(session).with(csrf()))
                .andExpect(status().isNoContent());

        assertThat(session.isInvalid()).isTrue();
        mockMvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void realCsrfFlowRequiresFreshTokenAfterLogin() throws Exception {
        String username = uniqueUsername();
        createUser(username);
        MockHttpSession session = new MockHttpSession();

        JsonNode token = fetchCsrfToken(session);
        assertThat(token.get("headerName").asText()).isEqualTo("X-CSRF-TOKEN");
        mockMvc.perform(post("/api/auth/login").session(session)
                        .header("X-CSRF-TOKEN", token.get("token").asText())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("identifier", username, "password", PASSWORD))))
                .andExpect(status().isOk());

        // The token is rotated at login, so the pre-login token can no longer be used.
        mockMvc.perform(post("/api/auth/logout").session(session).header("X-CSRF-TOKEN", token.get("token").asText()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
        mockMvc.perform(post("/api/auth/logout").session(session)
                        .header("X-CSRF-TOKEN", fetchCsrfToken(session).get("token").asText()))
                .andExpect(status().isNoContent());
    }

    @Test
    void apiRoutingErrorsUseProblemDetails() throws Exception {
        mockMvc.perform(get("/api/auth/login").with(user("someone")))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void protectedApiRoutesUseConsistentStatusCodes() throws Exception {
        mockMvc.perform(get("/api/peers")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/peers").with(csrf())).andExpect(status().isUnauthorized());

        // Authenticated but not yet implemented: 404, not a leak of anything else.
        mockMvc.perform(get("/api/files").with(user("someone")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        // Authenticated but lacking the required role.
        mockMvc.perform(get("/api/admin/users").with(user("someone")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    private ResultActions register(Map<String, ?> body) throws Exception {
        return mockMvc.perform(post("/api/auth/register").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private ResultActions login(MockHttpSession session, String identifier, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login").session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("identifier", identifier, "password", password))));
    }

    private JsonNode fetchCsrfToken(MockHttpSession session) throws Exception {
        String body = mockMvc.perform(get("/api/auth/csrf").session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private static Map<String, String> validRegistration(String username, String email) {
        return Map.of("username", username, "email", email, "displayName", "Someone",
                "password", PASSWORD, "confirmPassword", PASSWORD);
    }
}
