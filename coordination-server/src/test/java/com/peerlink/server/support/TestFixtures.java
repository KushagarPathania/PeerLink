package com.peerlink.server.support;

import com.peerlink.server.entity.FileTransfer;
import com.peerlink.server.entity.Peer;
import com.peerlink.server.entity.SharePermission;
import com.peerlink.server.entity.SharedFile;
import com.peerlink.server.entity.User;
import java.net.InetAddress;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

/** Persists valid, uniquely named entities so each test only spells out what it cares about. */
public final class TestFixtures {

    /** SHA-256 of "hello". */
    public static final String CHECKSUM = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";
    public static final String PASSWORD_HASH = "$2a$10$7EqJtq98hPqEX7fNZaFWoO5L7pMYSKsHyw3pT6Kd0hOLjvYJ8lZ1G";
    public static final int CHUNK_SIZE = 256;

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private final TestEntityManager entityManager;

    public TestFixtures(TestEntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public User user() {
        int n = SEQUENCE.incrementAndGet();
        return entityManager.persistAndFlush(new User("user" + n, "user" + n + "@example.com", PASSWORD_HASH));
    }

    public Peer peer(User owner) {
        int n = SEQUENCE.incrementAndGet();
        return entityManager.persistAndFlush(new Peer(owner, "device-" + n, InetAddress.getLoopbackAddress(), 9090));
    }

    public Peer onlinePeer(User owner, Instant lastHeartbeat) {
        Peer peer = peer(owner);
        peer.recordHeartbeat(InetAddress.getLoopbackAddress(), 9090, lastHeartbeat);
        return entityManager.persistAndFlush(peer);
    }

    public SharedFile file(Peer sourcePeer) {
        return file(sourcePeer, "file-" + SEQUENCE.incrementAndGet() + ".txt");
    }

    public SharedFile file(Peer sourcePeer, String fileName) {
        return entityManager.persistAndFlush(new SharedFile(sourcePeer, fileName, 1000, CHECKSUM, CHUNK_SIZE));
    }

    public SharePermission permission(SharedFile file, User permittedUser, Instant expiresAt) {
        return entityManager.persistAndFlush(new SharePermission(file, permittedUser, expiresAt));
    }

    public FileTransfer transfer(SharedFile file, Peer receiverPeer) {
        return entityManager.persistAndFlush(new FileTransfer(file, receiverPeer, receiverPeer.getUser()));
    }
}
