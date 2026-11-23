package org.university.judge.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class JudgeWorkerApplication {
    public static void main(String[] args) {
        SpringApplication.run(JudgeWorkerApplication.class, args);
    }
}
