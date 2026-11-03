package com.peerlink.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Receiver's report that it verified and saved the file. The server checks both values against
 * the registered metadata; it cannot see the file itself.
 */
public record TransferCompletionRequest(
        @PositiveOrZero long bytesTransferred,
        @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$", message = "must be a lower-case hexadecimal SHA-256 checksum")
        String sha256Checksum) {
}
