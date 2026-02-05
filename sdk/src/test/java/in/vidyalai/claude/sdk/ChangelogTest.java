package in.vidyalai.claude.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for validating CHANGELOG.md structure and format.
 *
 * Validates:
 * - Changelog exists
 * - Has correct header
 * - Valid version format (X.Y.Z with optional date)
 * - Versions in descending order
 * - Each version has bullet points
 * - No empty bullet points
 */
class ChangelogTest {

    private Path changelogPath;

    @BeforeEach
    void setup() {
        // Navigate from sdk/src/test/java to project root
        changelogPath = Path.of("").toAbsolutePath().getParent().resolve("docs/CHANGELOG.md");
    }

    @Test
    void testChangelogExists() {
        assertThat(changelogPath).exists();
    }

    @Test
    void testChangelogStartsWithHeader() throws IOException {
        String content = Files.readString(changelogPath);
        assertThat(content).startsWith("# Changelog");
    }

    @SuppressWarnings("null")
    @Test
    void testChangelogHasValidVersionFormat() throws IOException {
        String content = Files.readString(changelogPath);
        String[] lines = content.split("\n");

        // Pattern: ## X.Y.Z (YYYY-MM-DD) or ## X.Y.Z
        Pattern versionPattern = Pattern.compile("^## \\[\\d+\\.\\d+\\.\\d+\\] - \\d{4}-\\d{2}-\\d{2}$");
        Pattern versionExtractPattern = Pattern.compile("^## \\[(\\d+\\.\\d+\\.\\d+)\\]");
        List<String> versions = new ArrayList<>();

        for (String line : lines) {
            if (line.startsWith("## ")) {
                Matcher matcher = versionPattern.matcher(line);
                assertThat(matcher.matches())
                        .withFailMessage("Invalid version format: " + line)
                        .isTrue();

                // Extract version number
                Matcher versionMatcher = versionExtractPattern.matcher(line);
                if (versionMatcher.find()) {
                    versions.add(versionMatcher.group(1));
                }
            }
        }

        assertThat(versions).isNotEmpty();
    }

    @SuppressWarnings("null")
    @Test
    void testChangelogHasBulletPoints() throws IOException {
        String content = Files.readString(changelogPath);
        String[] lines = content.split("\n");

        boolean inVersionSection = false;
        boolean hasBulletPoints = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            if (line.startsWith("## ")) {
                if (inVersionSection && !hasBulletPoints) {
                    fail("Previous version section should have at least one bullet point");
                }
                inVersionSection = true;
                hasBulletPoints = false;
            } else if (inVersionSection && line.startsWith("- ")) {
                hasBulletPoints = true;
            } else if (inVersionSection && line.trim().isEmpty() && i == lines.length - 1) {
                // Last line check
                assertThat(hasBulletPoints)
                        .withFailMessage("Each version should have at least one bullet point")
                        .isTrue();
            }
        }

        // Check the last section
        if (inVersionSection) {
            assertThat(hasBulletPoints)
                    .withFailMessage("Last version section should have at least one bullet point")
                    .isTrue();
        }
    }

    @SuppressWarnings("null")
    @Test
    void testChangelogVersionsInDescendingOrder() throws IOException {
        String content = Files.readString(changelogPath);
        String[] lines = content.split("\n");

        List<Version> versions = new ArrayList<>();
        Pattern versionPattern = Pattern.compile("^## (\\d+)\\.(\\d+)\\.(\\d+)");

        for (String line : lines) {
            if (line.startsWith("## ")) {
                Matcher matcher = versionPattern.matcher(line);
                if (matcher.find()) {
                    int major = Integer.parseInt(matcher.group(1));
                    int minor = Integer.parseInt(matcher.group(2));
                    int patch = Integer.parseInt(matcher.group(3));
                    versions.add(new Version(major, minor, patch));
                }
            }
        }

        // Check versions are in descending order
        for (int i = 1; i < versions.size(); i++) {
            Version prev = versions.get(i - 1);
            Version curr = versions.get(i);

            assertThat(prev.compareTo(curr))
                    .withFailMessage(
                            "Versions should be in descending order: %s should be > %s",
                            prev, curr)
                    .isGreaterThan(0);
        }
    }

    @SuppressWarnings("null")
    @Test
    void testChangelogNoEmptyBulletPoints() throws IOException {
        String content = Files.readString(changelogPath);
        String[] lines = content.split("\n");

        for (String line : lines) {
            assertThat(line.trim())
                    .withFailMessage("Changelog should not have empty bullet points")
                    .isNotEqualTo("-");
        }
    }

    /**
     * Version record for comparison.
     */
    record Version(int major, int minor, int patch) implements Comparable<Version> {
        @Override
        public int compareTo(Version other) {
            if (this.major != other.major) {
                return Integer.compare(this.major, other.major);
            }
            if (this.minor != other.minor) {
                return Integer.compare(this.minor, other.minor);
            }
            return Integer.compare(this.patch, other.patch);
        }

        @Override
        public String toString() {
            return major + "." + minor + "." + patch;
        }
    }
}
