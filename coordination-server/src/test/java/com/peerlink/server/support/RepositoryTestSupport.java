package com.peerlink.server.support;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

/**
 * Repository slice tests against PostgreSQL with the real Flyway schema. Each test runs in a
 * transaction that is rolled back afterwards.
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
public abstract class RepositoryTestSupport extends PostgresContainerSupport {

    @Autowired
    protected TestEntityManager entityManager;

    protected TestFixtures fixtures;

    @BeforeEach
    void createFixtures() {
        fixtures = new TestFixtures(entityManager);
    }

    /**
     * Runs raw SQL that bypasses JPA and Bean Validation, so the database's own constraints are
     * what is being tested. PostgreSQL aborts the transaction afterwards, so call this last.
     */
    protected void assertSqlViolates(String constraintName, String sql, Object... parameters) {
        assertThatThrownBy(() -> executeSql(sql, parameters)).hasStackTraceContaining(constraintName);
    }

    protected int executeSql(String sql, Object... parameters) {
        var query = entityManager.getEntityManager().createNativeQuery(sql);
        for (int i = 0; i < parameters.length; i++) {
            query.setParameter(i + 1, parameters[i]);
        }
        return query.executeUpdate();
    }
}
