package in.vidyalai.claude.sdk.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Tests for SDK version information.
 */
class SdkVersionTest {

    @Test
    void testVersionIsNotNull() {
        assertThat(SdkVersion.VERSION).isNotNull();
    }

    @Test
    void testVersionIsNotEmpty() {
        assertThat(SdkVersion.VERSION).isNotEmpty();
    }

    @Test
    void testVersionMatchesPomVersion() {
        // The version should be in the format x.y.z or x.y.z-SNAPSHOT
        assertThat(SdkVersion.VERSION)
                .matches("\\d+\\.\\d+\\.\\d+(-SNAPSHOT)?");
    }

    @Test
    void testVersionStartsWith0_1_0() {
        // Verify it matches the current pom.xml version
        assertThat(SdkVersion.VERSION).startsWith("0.1.0");
    }

}
