package ru.provless.integration.chat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;
import java.util.UUID;

/**
 * The chat-service attachment bucket: the user's files are read from it for Chatwoot, the managers' files are copied
 * into it under {@code <prefix><user>/<file>} for the app (chat-service then gives the app signed links).
 */
@Component
public class ChatStorage {

    private final S3Client s3;
    private final String bucket;
    private final String prefix;

    public ChatStorage(@Value("${chatwoot.chat-storage.endpoint}") String endpoint,
                       @Value("${chatwoot.chat-storage.region}") String region,
                       @Value("${chatwoot.chat-storage.bucket}") String bucket,
                       @Value("${chatwoot.chat-storage.prefix}") String prefix,
                       @Value("${chatwoot.chat-storage.access-key}") String accessKey,
                       @Value("${chatwoot.chat-storage.secret-key}") String secretKey) {
        this.s3 = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        accessKey == null ? "" : accessKey, secretKey == null ? "" : secretKey)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
        this.bucket = bucket;
        this.prefix = prefix;
    }

    public byte[] read(String bucketName, String objectKey) {
        return s3.getObjectAsBytes(b -> b.bucket(bucketName).key(objectKey)).asByteArray();
    }

    /** @return the object key of the stored copy */
    public String storeForUser(UUID userId, String contentType, byte[] content) {
        String key = prefix + userId + "/" + UUID.randomUUID();
        s3.putObject(b -> b.bucket(bucket).key(key).contentType(contentType), RequestBody.fromBytes(content));
        return key;
    }
}
