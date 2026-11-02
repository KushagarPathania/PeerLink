package com.peerlink.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Metadata for a file a user has chosen to share from one of their devices. The file's bytes
 * and its local path stay on the source peer; the server only knows the bare file name.
 */
@Entity
@Table(name = "shared_files")
public class SharedFile extends BaseEntity {

    /** A bare file name: no directory separators, control characters, "." or "..". */
    public static final String FILE_NAME_PATTERN = "^(?!\\.{1,2}$)[^/\\\\\\p{Cntrl}]+$";
    public static final String SHA256_PATTERN = "^[0-9a-f]{64}$";
    public static final int FILE_NAME_MAX_LENGTH = 255;
    public static final int MAX_CHUNK_SIZE_BYTES = 16 * 1024 * 1024;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false, updatable = false)
    private User owner;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_peer_id", nullable = false, updatable = false)
    private Peer sourcePeer;

    @NotBlank
    @Size(max = FILE_NAME_MAX_LENGTH)
    @Pattern(regexp = FILE_NAME_PATTERN)
    @Column(name = "file_name", nullable = false, length = FILE_NAME_MAX_LENGTH)
    private String fileName;

    @PositiveOrZero
    @Column(name = "file_size_bytes", nullable = false)
    private long fileSizeBytes;

    @NotNull
    @Pattern(regexp = SHA256_PATTERN)
    @Column(name = "sha256_checksum", nullable = false, length = 64)
    private String sha256Checksum;

    @Size(max = 255)
    @Column(name = "content_type")
    private String contentType;

    @Positive
    @Max(MAX_CHUNK_SIZE_BYTES)
    @Column(name = "chunk_size_bytes", nullable = false)
    private int chunkSizeBytes;

    @PositiveOrZero
    @Column(name = "total_chunks", nullable = false)
    private int totalChunks;

    @Column(name = "available", nullable = false)
    private boolean available = true;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SharedFile() {
    }

    public SharedFile(Peer sourcePeer, String fileName, long fileSizeBytes, String sha256Checksum, int chunkSizeBytes) {
        Objects.requireNonNull(sourcePeer, "sourcePeer");
        if (fileSizeBytes < 0) {
            throw new IllegalArgumentException("File size must not be negative");
        }
        if (chunkSizeBytes < 1 || chunkSizeBytes > MAX_CHUNK_SIZE_BYTES) {
            throw new IllegalArgumentException("Chunk size must be between 1 and " + MAX_CHUNK_SIZE_BYTES + " bytes");
        }
        // The owner is always the source peer's user, so the two can never disagree.
        this.owner = sourcePeer.getUser();
        this.sourcePeer = sourcePeer;
        this.fileName = fileName;
        this.fileSizeBytes = fileSizeBytes;
        this.sha256Checksum = sha256Checksum == null ? null : sha256Checksum.toLowerCase(Locale.ROOT);
        this.chunkSizeBytes = chunkSizeBytes;
        this.totalChunks = calculateTotalChunks(fileSizeBytes, chunkSizeBytes);
    }

    static int calculateTotalChunks(long fileSizeBytes, int chunkSizeBytes) {
        try {
            return Math.toIntExact(Math.ceilDiv(fileSizeBytes, chunkSizeBytes));
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("File needs too many chunks; use a larger chunk size", e);
        }
    }

    public User getOwner() {
        return owner;
    }

    public Peer getSourcePeer() {
        return sourcePeer;
    }

    public String getFileName() {
        return fileName;
    }

    public long getFileSizeBytes() {
        return fileSizeBytes;
    }

    public String getSha256Checksum() {
        return sha256Checksum;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType == null || contentType.isBlank() ? null : contentType.trim();
    }

    public int getChunkSizeBytes() {
        return chunkSizeBytes;
    }

    public int getTotalChunks() {
        return totalChunks;
    }

    public boolean isAvailable() {
        return available;
    }

    public void markAvailable() {
        this.available = true;
    }

    public void markUnavailable() {
        this.available = false;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public String toString() {
        return "SharedFile{id=" + getId() + ", fileName='" + fileName + "', sizeBytes=" + fileSizeBytes + '}';
    }
}
