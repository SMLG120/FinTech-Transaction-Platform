package com.fintech.platform.common.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Keeps the topic catalogue in Java and the topic catalogue provisioned on disk from diverging.
 *
 * <p>The two are separate on purpose: {@code KafkaTopics} is what the code compiles against, and
 * {@code topics.txt} is what {@code kafka-init} creates in the broker. A topic that exists in one
 * and not the other is not a compile error and is not a startup error either, because publishing
 * to a missing topic either fails at the first publish or, with auto-creation left on, silently
 * creates it with the wrong partition count and no retention policy. Either way it is found in
 * production rather than in a build, so this test makes it a build failure instead.
 */
class TopicCatalogueTest {

    private static final Path TOPICS_FILE = locateTopicsFile();

    @Test
    @DisplayName("every topic the code publishes is provisioned on the broker")
    void provisionedTopicsMatchCode() throws IOException {
        List<String> provisioned = readProvisionedTopics();

        assertThat(provisioned)
                .as("topics in infrastructure/kafka/topics.txt that no code constant declares")
                .containsExactlyInAnyOrderElementsOf(KafkaTopics.ALL);
    }

    @Test
    @DisplayName("no topic is listed twice, because a duplicate would silently re-partition it")
    void noDuplicateTopics() throws IOException {
        assertThat(readProvisionedTopics()).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("topic names are lowercase hyphenated, so they survive relaxed binding")
    void topicNamesAreBindingSafe() {
        assertThat(KafkaTopics.ALL)
                .allSatisfy(topic -> assertThat(topic)
                        .as("topic name")
                        .matches("[a-z][a-z0-9]*(-[a-z0-9]+)*")
                        .doesNotContain("_", ".", " "));
    }

    private static List<String> readProvisionedTopics() throws IOException {
        try (Stream<String> lines = Files.lines(TOPICS_FILE, StandardCharsets.UTF_8)) {
            return lines.map(String::trim)
                    // Comments and blank lines are the file's documentation; they are not topics.
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
        }
    }

    /**
     * Finds the catalogue by walking up from the working directory.
     *
     * <p>Maven runs the module's tests with the module directory as the working directory, but an IDE
     * usually runs them from the repository root. Hard-coding {@code ../../} would pass under Maven
     * and fail in the IDE, which is a bad trade for a test whose whole job is to be trusted.
     */
    private static Path locateTopicsFile() {
        Path directory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve("infrastructure/kafka/topics.txt");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            directory = directory.getParent();
        }
        throw new UncheckedIOException(
                new IOException("infrastructure/kafka/topics.txt not found above " + System.getProperty("user.dir")));
    }
}
