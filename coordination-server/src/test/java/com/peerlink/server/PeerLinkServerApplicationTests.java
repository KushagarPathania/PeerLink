package com.peerlink.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.peerlink.server.dto.HealthResponse;
import com.peerlink.server.support.PostgresContainerSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Boots the full application against a real PostgreSQL instance in Docker, so Flyway, JPA and
 * the health check are exercised exactly as in production. Context startup itself proves that
 * Hibernate's schema validation accepts the Flyway schema. Skipped when Docker is unavailable.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PeerLinkServerApplicationTests extends PostgresContainerSupport {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void healthEndpointReportsDatabaseUp() {
        ResponseEntity<HealthResponse> response = restTemplate.getForEntity("/api/health", HealthResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(HealthResponse.Status.UP);
        assertThat(response.getBody().database()).isEqualTo(HealthResponse.Status.UP);
    }

    @Test
    void flywayAppliesAllMigrationsAndTheyValidate() {
        assertThat(flyway.info().applied())
                .extracting(migration -> migration.getVersion().getVersion())
                .containsExactly("1", "2", "3", "4");
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    }

    @Test
    void coreTablesExist() {
        assertThat(jdbcTemplate.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'", String.class))
                .contains("users", "peers", "shared_files", "share_permissions", "file_transfers", "transfer_events", "connection_tickets");
    }

    @Test
    void landingPageIsPublic() {
        ResponseEntity<String> response = restTemplate.getForEntity("/", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("PeerLink");
    }
}
