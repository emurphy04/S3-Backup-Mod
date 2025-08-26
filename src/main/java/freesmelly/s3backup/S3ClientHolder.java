package freesmelly.s3backup;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

public final class S3ClientHolder {
    private S3ClientHolder() {}

    private static volatile S3Client S3;

    public static synchronized void rebuild(String region) {
        if (S3 != null) {
            try { S3.close(); } catch (Throwable ignored) {}
            S3 = null;
        }
        init(region);
    }

    public static void init(String region) {
        if (S3 != null) return;
        synchronized (S3ClientHolder.class) {
            if (S3 == null) {
                S3ClientBuilder builder = S3Client.builder()
                        .region(Region.of(region))
                        .httpClient(UrlConnectionHttpClient.create());

                var inline = CredentialsStore.load();
                if (inline != null && inline.accessKeyId != null && inline.secretAccessKey != null) {
                    var creds = (inline.sessionToken == null || inline.sessionToken.isBlank())
                            ? AwsBasicCredentials.create(inline.accessKeyId, inline.secretAccessKey)
                            : AwsSessionCredentials.create(inline.accessKeyId, inline.secretAccessKey, inline.sessionToken);
                    builder.credentialsProvider(StaticCredentialsProvider.create(creds));
                } else {
                    builder.credentialsProvider(DefaultCredentialsProvider.create());
                }

                S3 = builder.build();
            }
        }
    }

    public static S3Client client() { return S3; }
}
