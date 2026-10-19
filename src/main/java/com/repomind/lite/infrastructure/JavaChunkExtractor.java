package com.repomind.lite.infrastructure;

import com.github.javaparser.*;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.*;
import com.repomind.lite.domain.CodeChunk;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

@Component
public class JavaChunkExtractor {
    public static final int MAX_FILES = 10000, MAX_CHUNKS = 20000, MAX_FILE_BYTES = 1024 * 1024, MAX_CHARS = 6000;

    public record Extraction(List<CodeChunk> chunks, List<String> warnings, int filesProcessed) {
    }

    public Extraction extract(UUID repositoryId, Path root) throws IOException {
        var chunks = new ArrayList<CodeChunk>();
        var warnings = new ArrayList<String>();
        int count = 0;
        var parser = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));
        try (Stream<Path> paths = Files.walk(root)) {
            var iterator = paths.filter(p -> !p.startsWith(root.resolve(".git")) && p.toString().endsWith(".java") && Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).iterator();
            while (iterator.hasNext()) {
                Path file = iterator.next();
                if (++count > MAX_FILES) throw new IOException("Repository exceeds Java file limit");
                String path = root.relativize(file).toString().replace('\\', '/');
                String source;
                try {
                    if (Files.size(file) > MAX_FILE_BYTES) {
                        warnings.add(path + ": file exceeds size limit");
                        continue;
                    }
                    source = Files.readString(file, StandardCharsets.UTF_8);
                } catch (IOException ex) {
                    warnings.add(path + ": file could not be read as UTF-8");
                    continue;
                }
                var parsed = parser.parse(source);
                if (!parsed.isSuccessful()) {
                    warnings.add(path + ": Java parsing failed");
                    continue;
                }
                List<String> lines = source.lines().toList();
                for (Node node : parsed.getResult().orElseThrow().findAll(Node.class)) {
                    String symbol = null;
                    if (node instanceof TypeDeclaration<?> type) symbol = type.getNameAsString();
                    else if (node instanceof MethodDeclaration method)
                        symbol = owner(node) + "#" + method.getNameAsString();
                    else if (node instanceof ConstructorDeclaration constructor)
                        symbol = owner(node) + "#" + constructor.getNameAsString();
                    else if (node instanceof CompactConstructorDeclaration constructor)
                        symbol = owner(node) + "#" + constructor.getNameAsString();
                    if (symbol == null || node.getRange().isEmpty()) continue;
                    var range = node.getRange().orElseThrow();
                    if (node instanceof TypeDeclaration<?> type) {
                        String outline = typeOutline(type, lines);
                        splitText(repositoryId, path, symbol, range.begin.line, range.end.line, outline, chunks, warnings);
                    } else {
                        split(repositoryId, path, symbol, lines, range.begin.line, range.end.line, chunks, warnings);
                    }
                    if (chunks.size() > MAX_CHUNKS) throw new IOException("Repository exceeds chunk limit");
                }
            }
        }
        if (chunks.isEmpty()) warnings.add("No indexable Java symbols were found in this repository");
        return new Extraction(List.copyOf(chunks), List.copyOf(warnings), count);
    }

    private static String owner(Node node) {
        for (Node parent = node.getParentNode().orElse(null); parent != null; parent = parent.getParentNode().orElse(null))
            if (parent instanceof TypeDeclaration<?> type) return type.getNameAsString();
        return "<unknown>";
    }

    private static String typeOutline(TypeDeclaration<?> type, List<String> lines) {
        var range = type.getRange().orElseThrow();
        String source = String.join("\n", lines.subList(range.begin.line - 1, range.end.line));
        int openingBrace = source.indexOf('{');
        StringBuilder outline = new StringBuilder(openingBrace >= 0 ? source.substring(0, openingBrace + 1).trim() : source.trim());
        for (Node child : type.getChildNodes()) {
            if (!(child instanceof BodyDeclaration<?> member) || member.getRange().isEmpty()) continue;
            String declaration = memberOutline(member);
            if (declaration.isBlank()) continue;
            if (!outline.isEmpty()) outline.append('\n');
            outline.append(declaration.strip());
        }
        return outline.toString();
    }

    private static String memberOutline(BodyDeclaration<?> member) {
        if (member instanceof MethodDeclaration method) {
            return method.clone().removeBody().toString();
        }
        if (member instanceof ConstructorDeclaration constructor) {
            return signatureBeforeBody(constructor.toString());
        }
        if (member instanceof CompactConstructorDeclaration constructor) {
            return signatureBeforeBody(constructor.toString());
        }
        if (member instanceof TypeDeclaration<?> nested) {
            String declaration = nested.toString();
            int openingBrace = declaration.indexOf('{');
            return openingBrace >= 0 ? declaration.substring(0, openingBrace + 1) : declaration;
        }
        return member.toString();
    }

    private static String signatureBeforeBody(String declaration) {
        int openingBrace = declaration.indexOf('{');
        return openingBrace >= 0 ? declaration.substring(0, openingBrace + 1).trim() : declaration.trim();
    }

    private static void split(UUID repo, String path, String symbol, List<String> lines, int start, int end, List<CodeChunk> chunks, List<String> warnings) {
        int begin = start;
        StringBuilder text = new StringBuilder();
        for (int line = start; line <= end; line++) {
            String value = lines.get(line - 1);
            if (value.length() > MAX_CHARS) {
                warnings.add(path + ":" + line + ": long source line split into multiple chunks");
                if (!text.isEmpty()) add(repo, path, symbol, begin, line - 1, text.toString(), chunks);
                text.setLength(0);
                for (int offset = 0; offset < value.length(); offset += MAX_CHARS)
                    add(repo, path, symbol, line, line, value.substring(offset, Math.min(offset + MAX_CHARS, value.length())), chunks);
                begin = line + 1;
                continue;
            }
            if (text.length() + value.length() + 1 > MAX_CHARS && !text.isEmpty()) {
                add(repo, path, symbol, begin, line - 1, text.toString(), chunks);
                text.setLength(0);
                begin = line;
            }
            if (!text.isEmpty()) text.append('\n');
            text.append(value);
        }
        if (!text.isEmpty()) add(repo, path, symbol, begin, end, text.toString(), chunks);
    }

    private static void splitText(UUID repo, String path, String symbol, int start, int end, String content,
                                  List<CodeChunk> chunks, List<String> warnings) {
        if (content.length() <= MAX_CHARS) {
            add(repo, path, symbol, start, end, content, chunks);
            return;
        }
        warnings.add(path + ":" + start + ": type outline split into multiple chunks");
        for (int offset = 0; offset < content.length(); offset += MAX_CHARS) {
            add(repo, path, symbol, start, end, content.substring(offset, Math.min(offset + MAX_CHARS, content.length())), chunks);
        }
    }

    private static void add(UUID repo, String path, String symbol, int start, int end, String content, List<CodeChunk> chunks) {
        chunks.add(new CodeChunk(UUID.randomUUID(), repo, path, symbol, start, end, content));
    }
}
