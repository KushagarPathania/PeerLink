package com.peerlink.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** JSON body of {@code POST /api/auth/login}. {@code identifier} is a username or an email address. */
public record LoginRequest(
        @NotBlank
        @Size(max = 254)
        String identifier,

        @NotBlank
        @Size(max = 255)
        String password) {

    @Override
    public String toString() {
        return "LoginRequest[identifier=" + identifier + ", password=****]";
    }
}
