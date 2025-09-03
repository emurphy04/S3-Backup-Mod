package freesmelly.s3backup;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

final class S3Multipart {
    private S3Multipart() {}

    static void upload(S3Client s3, Path file, String bucket, String key, long partSizeBytes) throws IOException {
        final long MIN = 5L * 1024 * 1024;
        final long MAX = 5L * 1024 * 1024 * 1024;
        if (partSizeBytes < MIN) partSizeBytes = MIN;
        if (partSizeBytes > MAX) partSizeBytes = MAX;

        String uploadId = null;
        try (FileChannel fc = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = fc.size();
            uploadId = s3.createMultipartUpload(CreateMultipartUploadRequest.builder().bucket(bucket).key(key).build()).uploadId();

            List<CompletedPart> completed = new ArrayList<>();
            long position = 0L;
            int partNumber = 1;

            while (position < size) {
                long thisPart = Math.min(partSizeBytes, size - position);
                MappedByteBuffer buffer = fc.map(FileChannel.MapMode.READ_ONLY, position, thisPart);

                UploadPartResponse resp = s3.uploadPart(
                        UploadPartRequest.builder()
                                .bucket(bucket).key(key)
                                .uploadId(uploadId)
                                .partNumber(partNumber)
                                .contentLength(thisPart)
                                .build(),
                        RequestBody.fromByteBuffer(buffer)
                );

                completed.add(CompletedPart.builder().partNumber(partNumber).eTag(resp.eTag()).build());
                position += thisPart;
                partNumber++;
            }

            s3.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                    .bucket(bucket).key(key)
                    .uploadId(uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(completed).build())
                    .build());

        } catch (RuntimeException | IOException e) {
            if (uploadId != null) {
                try {
                    s3.abortMultipartUpload(AbortMultipartUploadRequest.builder().bucket(bucket).key(key).uploadId(uploadId).build());
                } catch (Exception ignored) {}
            }
            throw e;
        }
    }

    static boolean needsMultipart(Path file, long thresholdBytes) throws IOException {
        return Files.size(file) >= thresholdBytes;
    }
}
