package org.enthusia.tags.rewards.loreitems;

import net.enthusia.loreitems.api.v1.LoreDeliveryStatus;
import net.enthusia.loreitems.api.v1.LoreItemsServiceV1;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ReleasedLoreItemsApiContractTest {
    private static final String APPROVED_RELEASE_SHA256 =
        "7c862b0ae545d710a33267ad6e19a4ae26d97323e97f40707c1475c9f9ba7063";
    @Test
    void buildUsesPinnedProductionArtifactContainingStableV1Api() throws Exception {
        Path jar = Path.of(System.getProperty("loreitems.release.jar"));
        String configuredSha = System.getProperty("loreitems.release.sha256");

        assertEquals(APPROVED_RELEASE_SHA256, configuredSha,
            "the Maven release pin must match the approved production artifact");
        assertEquals(APPROVED_RELEASE_SHA256, sha256(jar),
            "the test artifact bytes must match the approved production artifact");
        try (JarFile release = new JarFile(jar.toFile())) {
            assertNotNull(release.getEntry("net/enthusia/loreitems/api/v1/LoreItemsServiceV1.class"));
            assertNotNull(release.getEntry("net/enthusia/loreitems/api/v1/LoreDeliveryResult.class"));
            assertNotNull(release.getEntry("net/enthusia/loreitems/api/v1/LoreDeliveryStatus.class"));
        }

        assertEquals(1, LoreItemsServiceV1.API_VERSION);
        assertEquals(
            java.util.Set.of(
                "ACCEPTED_QUEUED",
                "ALREADY_ACCEPTED",
                "UNKNOWN_DEFINITION",
                "SERVICE_UNAVAILABLE",
                "VALIDATION_FAILURE"),
            java.util.Arrays.stream(LoreDeliveryStatus.values())
                .map(Enum::name)
                .collect(java.util.stream.Collectors.toSet()));
    }

    private static String sha256(Path path) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read = input.read(buffer);
            while (read >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
                read = input.read(buffer);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
