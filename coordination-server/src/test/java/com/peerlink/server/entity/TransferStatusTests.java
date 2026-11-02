package com.peerlink.server.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class TransferStatusTests {

    @ParameterizedTest
    @CsvSource({
            "REQUESTED, AUTHORIZED", "REQUESTED, FAILED", "REQUESTED, CANCELLED",
            "AUTHORIZED, IN_PROGRESS", "AUTHORIZED, FAILED", "AUTHORIZED, CANCELLED",
            "IN_PROGRESS, COMPLETED", "IN_PROGRESS, FAILED", "IN_PROGRESS, CANCELLED"})
    void allowsLegalTransitions(TransferStatus from, TransferStatus to) {
        assertThat(from.canTransitionTo(to)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            "REQUESTED, IN_PROGRESS", "REQUESTED, COMPLETED", "AUTHORIZED, COMPLETED",
            "AUTHORIZED, REQUESTED", "IN_PROGRESS, AUTHORIZED", "IN_PROGRESS, IN_PROGRESS"})
    void rejectsIllegalTransitions(TransferStatus from, TransferStatus to) {
        assertThat(from.canTransitionTo(to)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = TransferStatus.class, names = {"COMPLETED", "FAILED", "CANCELLED"})
    void terminalStatusesAllowNoFurtherTransitions(TransferStatus status) {
        assertThat(status.isTerminal()).isTrue();
        assertThat(status.allowedNextStatuses()).isEmpty();
    }

    @Test
    void activeStatusesAreTheNonTerminalOnes() {
        assertThat(TransferStatus.activeStatuses()).allSatisfy(status -> assertThat(status.isTerminal()).isFalse());
        assertThat(TransferStatus.activeStatuses()).hasSize(3);
    }
}
