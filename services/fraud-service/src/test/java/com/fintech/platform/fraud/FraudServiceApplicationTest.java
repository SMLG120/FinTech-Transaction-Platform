package com.fintech.platform.fraud;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.core.annotation.AnnotatedElementUtils;

class FraudServiceApplicationTest {

    @Test
    @DisplayName("is a valid Spring Boot entry point")
    void isSpringBootConfiguration() {
        // @SpringBootApplication is a composed annotation, so the effective annotation has to be
        // resolved through the meta-annotation tree rather than read off the class directly.
        assertThat(AnnotatedElementUtils.hasAnnotation(FraudServiceApplication.class, SpringBootConfiguration.class))
                .isTrue();
    }

    @Test
    @DisplayName("scans only its own package, so platform cross-cutting behaviour arrives by auto-configuration")
    void scansOnlyItsOwnPackage() {
        AutoConfigurationPackage autoConfigurationPackage = AnnotatedElementUtils.findMergedAnnotation(
                FraudServiceApplication.class, AutoConfigurationPackage.class);

        assertThat(autoConfigurationPackage).isNotNull();
        assertThat(autoConfigurationPackage.basePackages()).isEmpty();
    }

    @Test
    @DisplayName("lives in the package its component scan covers")
    void packageIsComponentScannable() {
        String base = "com.fintech.platform";
        String expected = "fraud";
        String actual = FraudServiceApplication.class.getPackageName().substring(base.length() + 1);

        assertThat(actual).isEqualTo(expected);
    }
}
