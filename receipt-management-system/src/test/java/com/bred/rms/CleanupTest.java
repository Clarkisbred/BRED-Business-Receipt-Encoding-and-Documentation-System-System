package com.bred.rms;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards against the old emergency-response (GERMS) concepts creeping back in. */
class CleanupTest {
    private static final List<String> FORBIDDEN = List.of("germs", "bred emergency", "red cross", "incident", "dispatch", "responder", "evacuation");
    /** Swing's own term; not a leftover. */
    private static final String ALLOWED_PHRASE = "event dispatch thread";

    @Test
    void noGermsLeftovers() throws IOException {
        Path root = Path.of("").toAbsolutePath();
        assertTrue(Files.isDirectory(root.resolve("src")), "run the tests from the project root; not found: " + root.resolve("src"));
        List<Path> files = new ArrayList<>();
        for (String dir : List.of("src", "database", "supabase")) {
            Path d = root.resolve(dir);
            if (Files.isDirectory(d)) {
                try (Stream<Path> walk = Files.walk(d)) {
                    walk.filter(Files::isRegularFile).forEach(files::add);
                }
            }
        }
        try (Stream<Path> top = Files.list(root)) {
            top.filter(p -> Files.isRegularFile(p) && (p.getFileName().toString().endsWith(".md")
                    || p.getFileName().toString().equals("pom.xml")
                    || p.getFileName().toString().endsWith(".example"))).forEach(files::add);
        }
        List<String> hits = new ArrayList<>();
        for (Path f : files) {
            if (f.getFileName().toString().equals("CleanupTest.java") || f.getFileName().toString().endsWith(".class")) continue;
            String text;
            try {
                text = Files.readString(f).toLowerCase(Locale.ROOT).replace(ALLOWED_PHRASE, "")
                        .replace("eventdispatchthread", "");
            } catch (IOException notText) {
                continue; // binary file
            }
            for (String word : FORBIDDEN) if (text.contains(word)) hits.add(root.relativize(f) + " contains '" + word + "'");
        }
        assertTrue(hits.isEmpty(), "GERMS leftovers:\n" + String.join("\n", hits));
    }
}
