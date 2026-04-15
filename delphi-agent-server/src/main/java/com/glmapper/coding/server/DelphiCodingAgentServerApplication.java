package com.glmapper.coding.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

@SpringBootApplication(scanBasePackages = "com.glmapper")
@EnableMongoRepositories(basePackages = "com.glmapper.coding.core.mongo")
@ConfigurationPropertiesScan
public class DelphiCodingAgentServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(DelphiCodingAgentServerApplication.class, args);
    }
}
