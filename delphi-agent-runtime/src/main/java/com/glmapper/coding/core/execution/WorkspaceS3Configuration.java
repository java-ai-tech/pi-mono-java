package com.glmapper.coding.core.execution;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;

@Configuration
@ConditionalOnProperty(prefix = "pi.execution.workspace-storage", name = "type", havingValue = "snapshot")
public class WorkspaceS3Configuration {

    @Bean
    public S3Client workspaceS3Client(
            @Value("${pi.execution.workspace-storage.snapshot.endpoint:}") String endpoint,
            @Value("${pi.execution.workspace-storage.snapshot.region:us-east-1}") String region,
            @Value("${pi.execution.workspace-storage.snapshot.access-key:}") String accessKey,
            @Value("${pi.execution.workspace-storage.snapshot.secret-key:}") String secretKey,
            @Value("${pi.execution.workspace-storage.snapshot.path-style:true}") boolean pathStyle) {

        var builder = S3Client.builder()
                .region(Region.of(region))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(pathStyle)
                        .build());

        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }

        if (accessKey != null && !accessKey.isBlank() && secretKey != null && !secretKey.isBlank()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey, secretKey)));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }

        return builder.build();
    }
}
