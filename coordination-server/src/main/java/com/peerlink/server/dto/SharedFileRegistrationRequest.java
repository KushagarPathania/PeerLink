package com.peerlink.server.dto;

import com.peerlink.server.entity.SharedFile;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Metadata a peer agent reports for a file the user selected to share. Never contains bytes or paths. */
public record SharedFileRegistrationRequest(
        @NotNull
        UUID sourcePeerId,

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
        String contentType,

        @NotNull
        @Positive
        @Max(SharedFile.MAX_CHUNK_SIZE_BYTES)
        Integer chunkSizeBytes) {
}
