package com.peerlink.server.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.peerlink.server.exception.ApiErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;

/**
 * Writes RFC 9457 problem-detail JSON for security failures on {@code /api/**}, so API clients
 * get 401/403 responses instead of a redirect to the HTML login page.
 */
public class ApiSecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public ApiSecurityErrorHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        write(request, response, HttpStatus.UNAUTHORIZED, ApiErrorCode.UNAUTHENTICATED, "Authentication is required.");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        if (accessDeniedException instanceof CsrfException) {
            write(request, response, HttpStatus.FORBIDDEN, ApiErrorCode.CSRF_TOKEN_INVALID,
                    "A valid CSRF token is required. Fetch one from GET /api/auth/csrf.");
        } else {
            write(request, response, HttpStatus.FORBIDDEN, ApiErrorCode.FORBIDDEN,
                    "You do not have permission to access this resource.");
        }
    }

    private void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status,
                       ApiErrorCode code, String detail) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code.name());
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
