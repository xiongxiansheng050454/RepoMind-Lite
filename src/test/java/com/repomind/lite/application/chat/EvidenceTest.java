package com.repomind.lite.application.chat;

import com.repomind.lite.errors.ChatFailure;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class EvidenceTest {
    @Test
    void acceptsOnlyCitedSubrangesOfObservedEvidence() {
        Evidence evidence = new Evidence();
        evidence.add("src/App.java", 10, 20, "class App {}", "Retrieved code", Evidence.SourceKind.SEARCH_CODE);
        Evidence.Result ok = evidence.validate("Works [src/App.java:L12-L15]");
        assertThat(ok.verified()).hasSize(1);
        assertThat(ok.clean()).isTrue();
    }

    @Test
    void reportsOutOfRangeCitationsInsteadOfFailing() {
        Evidence evidence = new Evidence();
        evidence.add("src/App.java", 10, 20, "class App {}", "Retrieved code", Evidence.SourceKind.SEARCH_CODE);

        Evidence.Result result = evidence.validate("Good [src/App.java:L12-L15], bad [src/App.java:L1-L15], [Other.java:L1-L2], [src/App.java:L9-L3]");

        assertThat(result.verified()).hasSize(1);
        assertThat(result.unverified()).containsExactly("src/App.java:L1-L15", "Other.java:L1-L2", "src/App.java:L9-L3");
        assertThat(result.clean()).isFalse();
    }

    @Test
    void flagsAnswersWithoutCitationsWhenEvidenceExists() {
        Evidence evidence = new Evidence();
        evidence.add("src/App.java", 10, 20, "class App {}", "Retrieved code", Evidence.SourceKind.SEARCH_CODE);

        Evidence.Result result = evidence.validate("Trust me");

        assertThat(result.uncited()).isTrue();
        assertThat(result.verified()).isEmpty();
        assertThat(result.clean()).isFalse();
        assertThatThrownBy(() -> evidence.validate("  ")).isInstanceOf(ChatFailure.class);
    }

    @Test
    void requiresExplicitUncertaintyWhenNoEvidenceExists() {
        Evidence evidence = new Evidence();
        assertThat(evidence.validate("Repository cannot confirm this behavior.").verified()).isEmpty();
        assertThatThrownBy(() -> evidence.validate("It definitely supports this."))
                .isInstanceOf(ChatFailure.class);
    }

    @Test
    void prefersMostSpecificEvidenceAndDeduplicatesRepeatedCitations() {
        Evidence evidence = new Evidence();
        evidence.add("src/App.java", 10, 20, "class App {}", "Initial search result", Evidence.SourceKind.INITIAL_SEARCH);
        evidence.add("src/App.java", 12, 15, "void run() {}", "Code search result", Evidence.SourceKind.SEARCH_CODE);
        evidence.add("src/App.java", 12, 15, "void run() {}", "Code search result", Evidence.SourceKind.SEARCH_CODE);

        assertThat(evidence.validate("A [src/App.java:L12-L15] and again [src/App.java:L12-L15]").verified())
                .singleElement()
                .satisfies(source -> {
                    assertThat(source.content()).isEqualTo("void run() {}");
                    assertThat(source.kind()).isEqualTo(Evidence.SourceKind.SEARCH_CODE);
                });
    }

    @Test
    void prefersFileExcerptWhenSameRangeHasDifferentEvidence() {
        Evidence evidence = new Evidence();
        evidence.add("src/App.java", 12, 15, "search result", "Code search result", Evidence.SourceKind.SEARCH_CODE);
        evidence.add("src/App.java", 12, 15, "file excerpt", "File excerpt", Evidence.SourceKind.FILE_EXCERPT);

        assertThat(evidence.validate("[src/App.java:L12-L15]").verified().get(0).content()).isEqualTo("file excerpt");
    }
}
