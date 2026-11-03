package com.peerlink.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.peerlink.server.entity.User;
import com.peerlink.server.support.WebIntegrationTestSupport;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

class WebAuthIntegrationTests extends WebIntegrationTestSupport {

    @ParameterizedTest
    @ValueSource(strings = {"/", "/login", "/register", "/css/app.css", "/js/app.js", "/api/health"})
    void publicRoutesNeedNoAuthentication(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/dashboard", "/settings", "/anything/else"})
    void pagesAreProtectedByDefault(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("http://localhost/login"));
    }

    @Test
    void pagesSendSecurityHeaders() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(header().string("Content-Security-Policy", Matchers.containsString("frame-ancestors 'none'")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"));
    }

    @Test
    void registrationPageContainsCsrfTokenAndLabelledFields() throws Exception {
        mockMvc.perform(get("/register"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("name=\"_csrf\"")))
                .andExpect(content().string(Matchers.containsString("<label for=\"confirmPassword\"")));
    }

    @Test
    void registrationFormCreatesAccountAndRedirectsToLogin() throws Exception {
        String username = uniqueUsername();

        submitRegistration(username, username + "@example.com", PASSWORD, PASSWORD)
                .andExpect(redirectedUrl("/login?registered"));

        User stored = userRepository.findByUsername(username).orElseThrow();
        assertThat(passwordEncoder.matches(PASSWORD, stored.getPasswordHash())).isTrue();
        assertThat(stored.getDisplayName()).isEqualTo("Form User");
    }

    @Test
    void registrationFormShowsMismatchWithoutEchoingPasswords() throws Exception {
        String typed = "a-very-secret-passphrase";

        MvcResult result = submitRegistration(uniqueUsername(), "x@example.com", typed, typed + "-different")
                .andExpect(status().isOk())
                .andExpect(view().name("auth/register"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .contains("Passwords do not match")
                .doesNotContain(typed);
    }

    @Test
    void registrationFormShowsDuplicateUsernameMessage() throws Exception {
        String username = uniqueUsername();
        createUser(username);

        submitRegistration(username, "new-" + username + "@example.com", PASSWORD, PASSWORD)
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("That username is already taken.")));
    }

    @Test
    void registrationFormRejectsMissingCsrfToken() throws Exception {
        String username = uniqueUsername();

        mockMvc.perform(post("/register").param("username", username).param("email", username + "@example.com")
                        .param("displayName", "X").param("password", PASSWORD).param("confirmPassword", PASSWORD))
                .andExpect(status().isForbidden());

        assertThat(userRepository.existsByUsername(username)).isFalse();
    }

    @Test
    void formLoginWorksWithUsernameOrEmail() throws Exception {
        String username = uniqueUsername();
        createUser(username);

        mockMvc.perform(formLogin("/login").user(username).password(PASSWORD))
                .andExpect(redirectedUrl("/dashboard"))
                .andExpect(authenticated().withUsername(username).withRoles("USER"));
        mockMvc.perform(formLogin("/login").user(username + "@example.com").password(PASSWORD))
                .andExpect(authenticated().withUsername(username));
    }

    @Test
    void formLoginFailsWithGenericErrorForBadCredentials() throws Exception {
        String username = uniqueUsername();
        createUser(username);

        mockMvc.perform(formLogin("/login").user(username).password("wrong-password-123"))
                .andExpect(redirectedUrl("/login?error"))
                .andExpect(unauthenticated());
        mockMvc.perform(formLogin("/login").user("nobody-" + username).password(PASSWORD))
                .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void disabledAccountCannotSignIn() throws Exception {
        String username = uniqueUsername();
        User user = createUser(username);
        user.disable();
        userRepository.saveAndFlush(user);

        mockMvc.perform(formLogin("/login").user(username).password(PASSWORD))
                .andExpect(redirectedUrl("/login?disabled"))
                .andExpect(unauthenticated());
        mockMvc.perform(formLogin("/login").user(username).password("wrong-password-123"))
                .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void dashboardShowsOwnAccountWithoutSecrets() throws Exception {
        String username = uniqueUsername();
        User user = createUser(username);
        MockHttpSession session = formLoginSession(username);

        String html = mockMvc.perform(get("/dashboard").session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains(username).contains(username + "@example.com").contains("Sign out")
                .doesNotContain(user.getPasswordHash()).doesNotContain("$2a$");
        // Peer management is rendered client-side from the API; the page carries the CSRF token for it.
        assertThat(html).contains("id=\"devicesTable\"").contains("id=\"onlineList\"")
                .contains("id=\"mySharesList\"").contains("id=\"sharedWithMeList\"").contains("id=\"transfersTable\"")
                .contains("/js/dashboard.js").contains("name=\"_csrf_header\"");
    }

    @Test
    void signedInUsersAreSentFromLoginPageToDashboard() throws Exception {
        String username = uniqueUsername();
        createUser(username);

        mockMvc.perform(get("/login").session(formLoginSession(username))).andExpect(redirectedUrl("/dashboard"));
    }

    @Test
    void logoutInvalidatesSessionAndBlocksProtectedPages() throws Exception {
        String username = uniqueUsername();
        createUser(username);
        MockHttpSession session = formLoginSession(username);

        mockMvc.perform(post("/logout").session(session).with(csrf()))
                .andExpect(redirectedUrl("/login?logout"));

        assertThat(session.isInvalid()).isTrue();
        mockMvc.perform(get("/dashboard").session(session)).andExpect(redirectedUrl("http://localhost/login"));
    }

    @Test
    void logoutWithoutCsrfTokenIsRejectedAndSessionSurvives() throws Exception {
        String username = uniqueUsername();
        createUser(username);
        MockHttpSession session = formLoginSession(username);

        mockMvc.perform(post("/logout").session(session)).andExpect(status().isForbidden());

        assertThat(session.isInvalid()).isFalse();
        mockMvc.perform(get("/dashboard").session(session)).andExpect(status().isOk());
    }

    private MockHttpSession formLoginSession(String username) throws Exception {
        MvcResult result = mockMvc.perform(formLogin("/login").user(username).password(PASSWORD))
                .andExpect(authenticated())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private ResultActions submitRegistration(String username, String email, String password, String confirm)
            throws Exception {
        return mockMvc.perform(post("/register").with(csrf())
                .param("username", username)
                .param("email", email)
                .param("displayName", "Form User")
                .param("password", password)
                .param("confirmPassword", confirm));
    }
}
