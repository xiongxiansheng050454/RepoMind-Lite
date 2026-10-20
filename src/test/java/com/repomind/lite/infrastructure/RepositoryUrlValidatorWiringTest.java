package com.repomind.lite.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RepositoryUrlValidatorWiringTest {
    @Test
    void springInjectsSkipFlagFromProperty() throws Exception {
        assertThat(skipFlag(Map.of("repomind.security.skip-ip-validation", "true"))).isTrue();
        assertThat(skipFlag(Map.of("repomind.security.skip-ip-validation", "false"))).isFalse();
    }

    @Test
    void skipFlagDefaultsToFalseWhenPropertyMissing() throws Exception {
        assertThat(skipFlag(Map.of())).isFalse();
    }

    private static boolean skipFlag(Map<String, Object> properties) throws Exception {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
            context.register(DnsResolver.class, RepositoryUrlValidator.class);
            context.refresh();
            var field = RepositoryUrlValidator.class.getDeclaredField("skipIpValidation");
            field.setAccessible(true);
            return field.getBoolean(context.getBean(RepositoryUrlValidator.class));
        }
    }
}
