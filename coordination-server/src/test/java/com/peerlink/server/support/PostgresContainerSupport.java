package com.peerlink.server.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for tests that need a real PostgreSQL. One container is started per test JVM and
 * shared by every subclass (and every cached Spring context); Testcontainers removes it at JVM
 * exit. Tests are skipped, not failed, when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
public abstract class PostgresContainerSupport {

    @ServiceConnection
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }
}
