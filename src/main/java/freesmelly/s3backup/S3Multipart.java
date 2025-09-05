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
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

final class S3Multipart {
    private S3Multipart() {}

    static boolean needsMultipart(Path file, long thresholdBytes) throws IOException {
        return Files.size(file) >= thresholdBytes;
    }

    static void upload(S3Client s3, Path file, String bucket, String key, long partSizeBytes) throws IOException {
        final long min = 5L * 1024 * 1024;
        final long max = 5L * 1024 * 1024 * 1024;
        if (partSizeBytes < min) partSizeBytes = min;
        if (partSizeBytes > max) partSizeBytes = max;
        if (partSizeBytes > Integer.MAX_VALUE) partSizeBytes = Integer.MAX_VALUE;

        String uploadId = null;
        long startNs = System.nanoTime();

        try (FileChannel fc = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = fc.size();
            long partCount = (size + partSizeBytes - 1) / partSizeBytes;

            System.out.println("[S3Backup] Multipart upload (sequential) starting: " + key + " size=" + size + " bytes parts=" + partCount + " partSize=" + partSizeBytes);

            uploadId = s3.createMultipartUpload(
                    CreateMultipartUploadRequest.builder().bucket(bucket).key(key).build()
            ).uploadId();

            List<CompletedPart> completed = new ArrayList<>();
            long position = 0L;
            int partNumber = 1;
            AtomicLong uploaded = new AtomicLong(0L);

            while (position < size) {
                long thisPart = Math.min(partSizeBytes, size - position);
                byte[] bytes = new byte[(int) thisPart];
                ByteBuffer buf = ByteBuffer.wrap(bytes);
                long remaining = thisPart;
                while (remaining > 0) {
                    int n = fc.read(buf, position + (thisPart - remaining));
                    if (n < 0) break;
                    remaining -= n;
                }

                System.out.println("[S3Backup] Uploading part " + partNumber + "/" + partCount + " (" + thisPart + " bytes)");
                UploadPartResponse resp = s3.uploadPart(
                        UploadPartRequest.builder()
                                .bucket(bucket).key(key)
                                .uploadId(uploadId)
                                .partNumber(partNumber)
                                .contentLength(thisPart)
                                .build(),
                        RequestBody.fromBytes(bytes)
                );

                completed.add(CompletedPart.builder().partNumber(partNumber).eTag(resp.eTag()).build());
                long done = uploaded.addAndGet(thisPart);
                double pct = (done * 100.0) / size;
                System.out.println(String.format("[S3Backup] Progress %.2f%% (%d/%d bytes)", pct, done, size));

                position += thisPart;
                partNumber++;
            }

            s3.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                    .bucket(bucket).key(key)
                    .uploadId(uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(completed).build())
                    .build());

            double secs = (System.nanoTime() - startNs) / 1_000_000_000.0;
            double mbps = (size / (1024.0 * 1024.0)) / Math.max(0.001, secs);
            System.out.println(String.format("[S3Backup] Multipart complete: %s in %.2fs (%.2f MiB/s)", key, secs, mbps));

        } catch (RuntimeException | IOException e) {
            if (uploadId != null) {
                try { s3.abortMultipartUpload(AbortMultipartUploadRequest.builder().bucket(bucket).key(key).uploadId(uploadId).build()); } catch (Exception ignored) {}
            }
            throw e;
        }
    }

    static void uploadParallel(S3Client s3, Path file, String bucket, String key, long partSizeBytes, int parallelism) throws IOException {
        final long min = 5L * 1024 * 1024;
        final long max = 5L * 1024 * 1024 * 1024;
        if (partSizeBytes < min) partSizeBytes = min;
        if (partSizeBytes > max) partSizeBytes = max;
        if (partSizeBytes > Integer.MAX_VALUE) partSizeBytes = Integer.MAX_VALUE;
        if (parallelism < 1) parallelism = 1;

        String uploadId = null;
        ExecutorService pool = Executors.newFixedThreadPool(parallelism, r -> {
            Thread t = new Thread(r, "S3Multipart-Worker");
            t.setDaemon(true);
            return t;
        });

        long startNs = System.nanoTime();

        try {
            long size = Files.size(file);
            long partCountL = (size + partSizeBytes - 1) / partSizeBytes;
            if (partCountL > 10_000) throw new IOException("Too many parts for S3 multipart upload");
            int partCount = (int) partCountL;

            System.out.println("[S3Backup] Multipart upload (parallel) starting: " + key + " size=" + size + " bytes parts=" + partCount + " partSize=" + partSizeBytes + " threads=" + parallelism);

            uploadId = s3.createMultipartUpload(CreateMultipartUploadRequest.builder().bucket(bucket).key(key).build()).uploadId();

            AtomicLong uploaded = new AtomicLong(0L);
            List<Future<CompletedPart>> futures = new ArrayList<>(partCount);

            for (int i = 0; i < partCount; i++) {
                final int partNumber = i + 1;
                final long start = i * partSizeBytes;
                final long thisPart = Math.min(partSizeBytes, size - start);

                String finalUploadId = uploadId;
                futures.add(pool.submit(() -> {
                    try (FileChannel fc = FileChannel.open(file, StandardOpenOption.READ)) {
                        byte[] bytes = new byte[(int) thisPart];
                        ByteBuffer buf = ByteBuffer.wrap(bytes);
                        long remaining = thisPart;
                        while (remaining > 0) {
                            int n = fc.read(buf, start + (thisPart - remaining));
                            if (n < 0) break;
                            remaining -= n;
                        }
                        System.out.println("[S3Backup] Uploading part " + partNumber + "/" + partCount + " (" + thisPart + " bytes)");
                        UploadPartResponse resp = s3.uploadPart(
                                UploadPartRequest.builder()
                                        .bucket(bucket).key(key)
                                        .uploadId(finalUploadId)
                                        .partNumber(partNumber)
                                        .contentLength(thisPart)
                                        .build(),
                                RequestBody.fromBytes(bytes)
                        );
                        long done = uploaded.addAndGet(thisPart);
                        double pct = (done * 100.0) / size;
                        System.out.println(String.format("[S3Backup] Progress %.2f%% (%d/%d bytes)", pct, done, size));
                        return CompletedPart.builder().partNumber(partNumber).eTag(resp.eTag()).build();
                    }
                }));
            }

            List<CompletedPart> completed = new ArrayList<>(partCount);
            for (Future<CompletedPart> f : futures) {
                completed.add(f.get());
            }
            completed.sort(Comparator.comparingInt(CompletedPart::partNumber));

            s3.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                    .bucket(bucket).key(key)
                    .uploadId(uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(completed).build())
                    .build());

            double secs = (System.nanoTime() - startNs) / 1_000_000_000.0;
            double mbps = (size / (1024.0 * 1024.0)) / Math.max(0.001, secs);
            System.out.println(String.format("[S3Backup] Multipart complete: %s in %.2fs (%.2f MiB/s)", key, secs, mbps));

        } catch (Exception e) {
            if (uploadId != null) {
                try { s3.abortMultipartUpload(AbortMultipartUploadRequest.builder().bucket(bucket).key(key).uploadId(uploadId).build()); } catch (Exception ignored) {}
            }
            throw (e instanceof IOException) ? (IOException) e : new IOException(e);
        } finally {
            pool.shutdownNow();
        }
    }
}
