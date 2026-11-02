package com.peerlink.server.service;

import com.peerlink.server.dto.HealthResponse;
import com.peerlink.server.dto.HealthResponse.Status;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class HealthService {

    private static final Logger log = LoggerFactory.getLogger(HealthService.class);
    private static final int DB_VALIDATION_TIMEOUT_SECONDS = 2;

    private final DataSource dataSource;
    private final String serviceName;

    public HealthService(DataSource dataSource, @Value("${spring.application.name}") String serviceName) {
        this.dataSource = dataSource;
        this.serviceName = serviceName;
    }

    public HealthResponse checkHealth() {
        Status database = isDatabaseReachable() ? Status.UP : Status.DOWN;
        // The server cannot coordinate anything without its database, so overall health follows it.
        return new HealthResponse(database, serviceName, database, Instant.now());
    }

    private boolean isDatabaseReachable() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(DB_VALIDATION_TIMEOUT_SECONDS);
        } catch (SQLException e) {
            log.warn("Database health check failed: {}", e.getMessage());
            return false;
        }
    }
}
