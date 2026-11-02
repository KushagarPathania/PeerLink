package com.peerlink.server.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.peerlink.server.dto.HealthResponse;
import com.peerlink.server.dto.HealthResponse.Status;
import com.peerlink.server.security.PeerCredentialService;
import com.peerlink.server.security.SecurityConfig;
import com.peerlink.server.service.HealthService;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(HealthController.class)
@Import(SecurityConfig.class)
class HealthControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HealthService healthService;

    // Required by SecurityConfig (authentication manager, peer-agent filter); unused here.
    @MockitoBean
    private UserDetailsService userDetailsService;

    @MockitoBean
    private PeerCredentialService peerCredentialService;

    @Test
    void returnsOkWhenDatabaseIsUp() throws Exception {
        given(healthService.checkHealth()).willReturn(health(Status.UP));

        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.database").value("UP"))
                .andExpect(jsonPath("$.service").value("peerlink-coordination-server"));
    }

    @Test
    void returnsServiceUnavailableWhenDatabaseIsDown() throws Exception {
        given(healthService.checkHealth()).willReturn(health(Status.DOWN));

        mockMvc.perform(get("/api/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.database").value("DOWN"));
    }

    @Test
    void protectedApiRoutesReturnJson401InsteadOfRedirecting() throws Exception {
        mockMvc.perform(get("/api/peers"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    private static HealthResponse health(Status status) {
        return new HealthResponse(status, "peerlink-coordination-server", status, Instant.now());
    }
}
