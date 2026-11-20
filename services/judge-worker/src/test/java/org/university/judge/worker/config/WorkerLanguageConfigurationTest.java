package org.university.judge.worker.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import org.university.judge.worker.Fixtures;
import org.university.judge.worker.grading.LanguageProfiles;

class WorkerLanguageConfigurationTest {
    private static WorkerProperties bind(String enabled) {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        var environment = new MockEnvironment().withProperty("JUDGE_WORKER_TOKEN", "test-token");
        if (enabled != null) environment.setProperty("WORKER_LANGUAGES", enabled);
        environment.getPropertySources().addLast(new PropertiesPropertySource("application", yaml.getObject()));
        return Binder.get(environment).bind("judge.worker", WorkerProperties.class).get();
    }

    @Test
    void shippedConfigurationEnablesEveryApiLanguageWithACompilerImage() {
        var profiles = new LanguageProfiles(bind(null));
        assertEquals(Fixtures.ALL_LANGUAGES, profiles.enabledKeys());
        for (String key : profiles.enabledKeys()) {
            String image = profiles.find(key).orElseThrow().image();
            assertNotNull(image);
            assertFalse(image.isBlank());
        }
    }

    @Test
    void operatorCanRestrictLanguagesWithoutRecompiling() {
        var profiles = new LanguageProfiles(bind("PYTHON,RUST"));
        assertEquals(List.of("PYTHON", "RUST"), profiles.enabledKeys());
        assertTrue(profiles.find("JAVA").isEmpty());
    }
}
