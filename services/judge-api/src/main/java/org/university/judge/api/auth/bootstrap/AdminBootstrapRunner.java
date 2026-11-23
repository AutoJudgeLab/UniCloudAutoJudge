package org.university.judge.api.auth.bootstrap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@EnableConfigurationProperties(AdminBootstrapSettings.class)
@ConditionalOnProperty(name = "security.authentication-mode", havingValue = "local", matchIfMissing = true)
public class AdminBootstrapRunner implements ApplicationRunner {
    private final AdminBootstrapService bootstrap;

    public AdminBootstrapRunner(AdminBootstrapService bootstrap) {
        this.bootstrap = bootstrap;
    }

    @Override
    public void run(ApplicationArguments args) {
        bootstrap.provisionIfEnabled();
    }
}
