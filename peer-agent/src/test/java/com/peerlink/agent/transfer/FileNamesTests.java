package com.peerlink.agent.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FileNamesTests {

    @ParameterizedTest
    @ValueSource(strings = {"report.pdf", "Résumé 2026.docx", "a", ".env", "photo (1).jpg", "data.tar.gz"})
    void acceptsPlainFileNames(String name) {
        assertThat(FileNames.requireSafe(name)).isEqualTo(name);
    }

    @ParameterizedTest
    @ValueSource(strings = {"../secret", "..", ".", "dir/file", "/etc/passwd", "C:\\Windows\\x", "..\\..\\x",
            "name\u0000.txt", "line\nbreak", "trailing.", "trailing ", " ", ""})
    void rejectsTraversalAbsolutePathsSeparatorsAndControlCharacters(String name) {
        assertThatIllegalArgumentException().isThrownBy(() -> FileNames.requireSafe(name));
    }

    @Test
    void rejectsNamesLongerThan255Bytes() {
        assertThatIllegalArgumentException().isThrownBy(() -> FileNames.requireSafe("é".repeat(128)));
        assertThat(FileNames.requireSafe("a".repeat(255))).hasSize(255);
    }

    @Test
    void numbersCollidingNamesBeforeTheExtension() {
        assertThat(FileNames.numbered("report.pdf", 2)).isEqualTo("report (2).pdf");
        assertThat(FileNames.numbered("notes", 3)).isEqualTo("notes (3)");
        assertThat(FileNames.numbered(".env", 2)).isEqualTo(".env (2)");
        assertThat(FileNames.numbered("data.tar.gz", 2)).isEqualTo("data.tar (2).gz");
    }
}
