package com.peerlink.server.dto;

import com.peerlink.server.entity.SharedFile;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Metadata a peer agent reports for a file its user chose to share. The source peer is the
 * authenticated agent's own peer, never a field. Never contains file bytes or local paths.
 */
public record SharedFileRegistrationRequest(
        @NotBlank
        @Size(max = SharedFile.FILE_NAME_MAX_LENGTH)
        @Pattern(regexp = SharedFile.FILE_NAME_PATTERN, message = "must be a file name without directories")
        String fileName,

        @NotNull
        @PositiveOrZero
        Long fileSizeBytes,

        @NotBlank
        @Pattern(regexp = "^[0-9a-fA-F]{64}$", message = "must be a 64-character hexadecimal SHA-256 checksum")
        String sha256Checksum,

        @Size(max = 255)
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "must not contain control characters")
        String contentType,

        @NotNull
        @Min(value = SharedFile.MIN_TRANSFER_CHUNK_SIZE_BYTES, message = "must be between 4096 and 65536 bytes")
        @Max(value = SharedFile.MAX_TRANSFER_CHUNK_SIZE_BYTES, message = "must be between 4096 and 65536 bytes")
        Integer chunkSizeBytes) {
}
