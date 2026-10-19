package com.repomind.lite.infrastructure;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class JavaChunkExtractorTest {
    @TempDir
    Path root;

    @Test
    void extractsJava17SymbolsWithExactSourceRanges() throws Exception {
        Files.writeString(root.resolve("Demo.java"), "record Demo(int value) {\n  Demo { if(value<0) throw new IllegalArgumentException(); }\n  int twice() { return value * 2; }\n}\n");
        var result = new JavaChunkExtractor().extract(UUID.randomUUID(), root);
        assertThat(result.chunks()).extracting(c -> c.symbol()).contains("Demo", "Demo#Demo", "Demo#twice");
        var method = result.chunks().stream().filter(c -> c.symbol().endsWith("#twice")).findFirst().orElseThrow();
        assertThat(method.startLine()).isEqualTo(3);
        assertThat(method.endLine()).isEqualTo(3);
        assertThat(method.content()).isEqualTo("  int twice() { return value * 2; }");

        var type = result.chunks().stream().filter(c -> c.symbol().equals("Demo")).findFirst().orElseThrow();
        assertThat(type.content()).contains("record Demo(int value) {", "int twice()");
        assertThat(type.content()).doesNotContain("return value * 2");
    }

    @Test
    void classOutlineKeepsFieldsAndMethodSignaturesWithoutBodies() throws Exception {
        Files.writeString(root.resolve("Service.java"), "class Service {\n  private final String name = \"demo\";\n  String load(int id) { return name + id; }\n}\n");

        var chunks = new JavaChunkExtractor().extract(UUID.randomUUID(), root).chunks();
        var type = chunks.stream().filter(c -> c.symbol().equals("Service")).findFirst().orElseThrow();

        assertThat(type.content()).contains("class Service {", "private final String name = \"demo\";", "String load(int id)");
        assertThat(type.content()).doesNotContain("return name + id");
        assertThat(type.startLine()).isEqualTo(1);
        assertThat(type.endLine()).isEqualTo(4);
    }

    @Test
    void splitsLargeSymbolsAndKeepsRangesValid() throws Exception {
        Files.writeString(root.resolve("Large.java"), "class Large {\nvoid run() {\n" + "int x = 1; // padding padding padding\n".repeat(400) + "}\n}\n");
        var result = new JavaChunkExtractor().extract(UUID.randomUUID(), root);
        assertThat(result.chunks()).isNotEmpty().allSatisfy(c -> {
            assertThat(c.content().length()).isLessThanOrEqualTo(6000);
            assertThat(c.endLine()).isGreaterThanOrEqualTo(c.startLine());
        });
    }

    @Test
    void malformedJavaIsAWarning() throws Exception {
        Files.writeString(root.resolve("Broken.java"), "class Broken { public void (");
        assertThat(new JavaChunkExtractor().extract(UUID.randomUUID(), root).warnings()).isNotEmpty();
    }

    @Test
    void longSourceLineIsPreservedAcrossChunksWithActualLineRange() throws Exception {
        String source = "class Large { String run() { return \"" + "a".repeat(13000) + "\"; } }";
        Files.writeString(root.resolve("Large.java"), source);
        var chunks = new JavaChunkExtractor().extract(UUID.randomUUID(), root).chunks().stream().filter(c -> c.symbol().equals("Large#run")).toList();
        assertThat(chunks).hasSize(3).allSatisfy(c -> {
            assertThat(c.content().length()).isLessThanOrEqualTo(6000);
            assertThat(c.startLine()).isEqualTo(1);
            assertThat(c.endLine()).isEqualTo(1);
        });
        assertThat(chunks.stream().map(c -> c.content()).collect(java.util.stream.Collectors.joining())).isEqualTo(source);
    }

    @Test
    void invalidUtf8FileWarnsAndDoesNotDiscardHealthyFile() throws Exception {
        Files.write(root.resolve("Bad.java"), new byte[]{(byte) 0xc3, 0x28});
        Files.writeString(root.resolve("Good.java"), "class Good {}\n");
        var result = new JavaChunkExtractor().extract(UUID.randomUUID(), root);
        assertThat(result.chunks()).extracting(c -> c.symbol()).contains("Good");
        assertThat(result.warnings()).anyMatch(warning -> warning.contains("Bad.java"));
    }

    @Test
    void emptyCorpusReportsWarning() throws Exception {
        assertThat(new JavaChunkExtractor().extract(UUID.randomUUID(), root).warnings()).isNotEmpty();
    }
}
