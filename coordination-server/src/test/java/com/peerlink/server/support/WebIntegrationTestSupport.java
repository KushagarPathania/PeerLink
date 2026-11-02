package com.peerlink.server.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.peerlink.server.entity.User;
import com.peerlink.server.repository.UserRepository;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Full application (real security filter chains, services and PostgreSQL) driven through
 * MockMvc. Data is committed, so every test uses unique usernames.
 */
// The background sweep is off so it cannot race tests that set up stale peers deliberately.
@SpringBootTest(properties = "peerlink.peers.sweep-enabled=false")
@AutoConfigureMockMvc
public abstract class WebIntegrationTestSupport extends PostgresContainerSupport {

    protected static final String PASSWORD = "correct-horse-battery";

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    protected static String uniqueUsername() {
        return "auth" + SEQUENCE.incrementAndGet() + "_" + UUID.randomUUID().toString().substring(0, 6);
    }

    /** Creates an account directly in the database with {@link #PASSWORD}. */
    protected User createUser(String username) {
        User user = new User(username, username + "@example.com", passwordEncoder.encode(PASSWORD));
        user.setDisplayName("Test " + username);
        return userRepository.saveAndFlush(user);
    }

    protected String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    /** Logs in through the JSON API and returns the authenticated session. */
    protected MockHttpSession apiLogin(String identifier) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/api/auth/login").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("identifier", identifier, "password", PASSWORD))))
                .andExpect(status().isOk());
        return session;
    }
}
