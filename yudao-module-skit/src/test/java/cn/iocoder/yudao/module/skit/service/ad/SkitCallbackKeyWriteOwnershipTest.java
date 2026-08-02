package cn.iocoder.yudao.module.skit.service.ad;

import cn.iocoder.yudao.module.skit.dal.mysql.ad.SkitAdCallbackKeyMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the application-side half of the legacy-row to registry-row completeness invariant. */
class SkitCallbackKeyWriteOwnershipTest {

    @Test
    void credentialServiceIsTheOnlyProductionWriterOfLegacyCallbackKeys() throws IOException {
        Path sourceRoot = sourceRoot();
        List<Path> writers;
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            writers = files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> containsMapperInsert(path))
                    .collect(Collectors.toList());
        }

        assertEquals(1, writers.size(), "legacy callback-key inserts must have one owner");
        assertTrue(writers.get(0).endsWith(Paths.get("service", "ad",
                "SkitAdCredentialVersionServiceImpl.java")));
    }

    private static boolean containsMapperInsert(Path path) {
        try {
            String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            return source.contains(SkitAdCallbackKeyMapper.class.getSimpleName())
                    && source.contains("callbackKeyMapper.insert(");
        } catch (IOException exception) {
            throw new IllegalStateException("Could not inspect callback-key write ownership", exception);
        }
    }

    private static Path sourceRoot() {
        Path current = Paths.get(System.getProperty("user.dir"));
        Path moduleRoot = current.resolve("src/main/java");
        return Files.isDirectory(moduleRoot)
                ? moduleRoot
                : current.resolve("yudao-module-skit/src/main/java");
    }
}
