package com.peerlink.server.controller;

import com.peerlink.server.dto.CsrfTokenResponse;
import com.peerlink.server.dto.LoginRequest;
import com.peerlink.server.dto.UserRegistrationRequest;
import com.peerlink.server.dto.UserResponse;
import com.peerlink.server.security.AuthenticatedUser;
import com.peerlink.server.security.SessionLoginService;
import com.peerlink.server.service.AccountService;
import com.peerlink.server.service.RegistrationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Session-based authentication API. {@code POST /api/auth/logout} is handled by Spring
 * Security's logout filter (configured in SecurityConfig), which invalidates the session and
 * returns 204. All POST endpoints require a CSRF token from {@code GET /api/auth/csrf}.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthApiController {

    private final RegistrationService registrationService;
    private final SessionLoginService sessionLoginService;
    private final AccountService accountService;

    public AuthApiController(RegistrationService registrationService, SessionLoginService sessionLoginService,
                             AccountService accountService) {
        this.registrationService = registrationService;
        this.sessionLoginService = sessionLoginService;
        this.accountService = accountService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@Valid @RequestBody UserRegistrationRequest request) {
        return registrationService.register(request);
    }

    /** On success the session cookie identifies the new session; nothing secret is in the body. */
    @PostMapping("/login")
    public UserResponse login(@Valid @RequestBody LoginRequest request,
                              HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        AuthenticatedUser user = sessionLoginService.login(
                request.identifier(), request.password(), httpRequest, httpResponse);
        return accountService.getProfile(user.getId());
    }

    /** Always the caller's own profile; the user is taken from the session, never from the request. */
    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal AuthenticatedUser user) {
        return accountService.getProfile(user.getId());
    }

    @GetMapping("/csrf")
    public CsrfTokenResponse csrf(CsrfToken token) {
        return new CsrfTokenResponse(token.getHeaderName(), token.getParameterName(), token.getToken());
    }
}
