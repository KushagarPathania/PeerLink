package com.peerlink.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * A device running the peer agent on behalf of a user. The stored {@code status} is only a
 * hint: a peer counts as online solely while its heartbeats are recent (see {@link #isOnline}).
 * The agent credential is stored only as a SHA-256 hash and has no getter, so it cannot leak
 * through DTO mapping or serialization.
 */
@Entity
@Table(name = "peers")
public class Peer extends BaseEntity {

    public static final int MIN_PORT = 1;
    public static final int MAX_PORT = 65535;
    public static final int DEVICE_NAME_MAX_LENGTH = 100;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @NotBlank
    @Size(max = DEVICE_NAME_MAX_LENGTH)
    @Column(name = "device_name", nullable = false, length = DEVICE_NAME_MAX_LENGTH)
    private String deviceName;

    /**
     * Address the server observed on the agent's last heartbeat; never taken from the peer's own
     * claims. {@code null} until the first heartbeat.
     */
    @Column(name = "host_address")
    private InetAddress hostAddress;

    @Min(MIN_PORT)
    @Max(MAX_PORT)
    @Column(name = "listening_port", nullable = false)
    private int listeningPort;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PeerStatus status = PeerStatus.OFFLINE;

    @Column(name = "last_heartbeat_at")
    private Instant lastHeartbeatAt;

    @Column(name = "credential_hash", length = 64)
    private String credentialHash;

    @Column(name = "credential_issued_at")
    private Instant credentialIssuedAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Peer() {
    }

    /** A newly registered device: OFFLINE, with no known address until its agent sends a heartbeat. */
    public Peer(User user, String deviceName, int listeningPort) {
        this(user, deviceName, null, listeningPort);
    }

    public Peer(User user, String deviceName, InetAddress hostAddress, int listeningPort) {
        this.user = user;
        this.deviceName = deviceName == null ? null : deviceName.trim();
        this.hostAddress = hostAddress;
        this.listeningPort = listeningPort;
    }

    /** Records a heartbeat at server time {@code now} from the address the server observed. */
    public void recordHeartbeat(InetAddress observedAddress, Instant now) {
        recordHeartbeat(observedAddress, listeningPort, now);
    }

    public void recordHeartbeat(InetAddress observedAddress, int listeningPort, Instant now) {
        this.hostAddress = observedAddress;
        this.listeningPort = listeningPort;
        this.lastHeartbeatAt = now;
        this.status = PeerStatus.ONLINE;
    }

    public void markOffline() {
        this.status = PeerStatus.OFFLINE;
    }

    /** Replaces any existing agent credential; only the hash of the new credential is kept. */
    public void assignCredential(String credentialHash, Instant issuedAt) {
        this.credentialHash = credentialHash;
        this.credentialIssuedAt = issuedAt;
    }

    /** Revokes the agent credential. The agent can no longer send heartbeats, so the peer goes OFFLINE. */
    public void revokeCredential() {
        this.credentialHash = null;
        this.credentialIssuedAt = null;
        markOffline();
    }

    public boolean hasActiveCredential() {
        return credentialHash != null;
    }

    public Instant getCredentialIssuedAt() {
        return credentialIssuedAt;
    }

    public boolean isOnline(Instant now, Duration heartbeatTimeout) {
        return status == PeerStatus.ONLINE
                && lastHeartbeatAt != null
                && !lastHeartbeatAt.isBefore(now.minus(heartbeatTimeout));
    }

    public User getUser() {
        return user;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public void rename(String deviceName) {
        this.deviceName = deviceName == null ? null : deviceName.trim();
    }

    public InetAddress getHostAddress() {
        return hostAddress;
    }

    public int getListeningPort() {
        return listeningPort;
    }

    public PeerStatus getStatus() {
        return status;
    }

    public Instant getLastHeartbeatAt() {
        return lastHeartbeatAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public String toString() {
        return "Peer{id=" + getId() + ", deviceName='" + deviceName + "', status=" + status + '}';
    }
}
