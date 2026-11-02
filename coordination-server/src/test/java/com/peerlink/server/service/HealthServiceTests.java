package com.peerlink.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.peerlink.server.dto.HealthResponse;
import com.peerlink.server.dto.HealthResponse.Status;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class HealthServiceTests {

    private final DataSource dataSource = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final HealthService healthService = new HealthService(dataSource, "test-service");

    @Test
    void reportsUpWhenConnectionIsValid() throws SQLException {
        given(dataSource.getConnection()).willReturn(connection);
        given(connection.isValid(anyInt())).willReturn(true);

        HealthResponse health = healthService.checkHealth();

        assertThat(health.status()).isEqualTo(Status.UP);
        assertThat(health.database()).isEqualTo(Status.UP);
        assertThat(health.service()).isEqualTo("test-service");
        assertThat(health.timestamp()).isNotNull();
        verify(connection).close();
    }

    @Test
    void reportsDownWhenConnectionIsInvalid() throws SQLException {
        given(dataSource.getConnection()).willReturn(connection);
        given(connection.isValid(anyInt())).willReturn(false);

        assertThat(healthService.checkHealth().status()).isEqualTo(Status.DOWN);
    }

    @Test
    void reportsDownWhenDatabaseIsUnreachable() throws SQLException {
        given(dataSource.getConnection()).willThrow(new SQLException("Connection refused"));

        HealthResponse health = healthService.checkHealth();

        assertThat(health.status()).isEqualTo(Status.DOWN);
        assertThat(health.database()).isEqualTo(Status.DOWN);
    }
}
