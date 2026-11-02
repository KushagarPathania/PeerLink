package com.peerlink.server.dto;

/**
 * The CSRF token an API client must echo back in {@code headerName} on state-changing requests.
 * It is bound to the caller's session and is useless to anyone without that session cookie.
 */
public record CsrfTokenResponse(String headerName, String parameterName, String token) {
}
