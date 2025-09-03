package freesmelly.s3backup;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public final class BackupService {
    private BackupService() {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String MODID = "s3-backup-mod";

    private static class Config {
        int backupIntervalMinutes = 10;
        String s3Bucket = "";
        String s3Prefix = "mc-backups";
        String awsRegion = System.getenv().getOrDefault("AWS_REGION", "us-east-1");
        boolean keepLatestLocal = false;
        boolean deleteLocalAfterUpload = true;
        String zipBaseName = "world-backup";
        List<String> excludeGlobs = List.of("logs/**","backups/**","crash-reports/**");
        int keepLastNS3 = 5;
        int multipartThresholdMB = 100;
        int multipartPartSizeMB = 64;
    }

    private static volatile Config cfg;
    private static final ScheduledExecutorService IO = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "S3Backup-IO"); t.setDaemon(true); return t;
    });
    private static final AtomicLong ticks = new AtomicLong();
    private static volatile long ticksPerBackup = 20L * 60L * 10L;

    private static boolean originalLogAdmin = true;
    private static void setLogAdminCommands(MinecraftServer server, boolean value) {
        try {
            var rules = server.getGameRules();
            var rule = net.minecraft.world.GameRules.LOG_ADMIN_COMMANDS;
            originalLogAdmin = rules.get(rule).get();
            rules.get(rule).set(value, server);
        } catch (Throwable ignored) {}
    }
    private static void restoreLogAdmin(MinecraftServer server) {
        try {
            var rules = server.getGameRules();
            var rule = net.minecraft.world.GameRules.LOG_ADMIN_COMMANDS;
            rules.get(rule).set(originalLogAdmin, server);
        } catch (Throwable ignored) {}
    }

    public static void bootstrap() {
        cfg = loadOrCreateConfig();
        S3ClientHolder.init(cfg.awsRegion);

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, env) -> {
            dispatcher.register(
                    LiteralArgumentBuilder.<ServerCommandSource>literal("backupnow")
                            .requires(src -> src.hasPermissionLevel(3))
                            .executes(ctx -> {
                                var server = ctx.getSource().getServer();
                                ctx.getSource().sendFeedback(() -> Text.literal("[S3Backup] Starting backup…"), false);
                                startBackup(server);
                                return 1;
                            })
            );
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, ra, env) -> {
            var root = CommandManager.literal("s3setup")
                    .requires(src -> src.hasPermissionLevel(3))
                    .then(CommandManager.literal("wizard").executes(ctx -> {
                        var server = ctx.getSource().getServer();
                        setLogAdminCommands(server, false);
                        ctx.getSource().sendFeedback(() -> Text.literal(
                                "§a[S3Backup] Wizard started.\n" +
                                        "Step 1: /s3setup set region <aws-region>\n" +
                                        "Step 2: /s3setup set bucket <bucket-name>\n" +
                                        "Step 3: /s3setup set prefix <optional/key/prefix>\n" +
                                        "Step 4: /s3setup set keep 5\n" +
                                        "Step 5: /s3setup set accessKey <AKIA...>\n" +
                                        "Step 6: /s3setup set secretKey <your-secret>\n" +
                                        "Step 7: (optional) /s3setup set sessionToken <token>\n" +
                                        "Then: /s3setup test\n" +
                                        "Finally: /s3setup finish"), true);
                        return 1;
                    }))
                    .then(CommandManager.literal("finish").executes(ctx -> {
                        restoreLogAdmin(ctx.getSource().getServer());
                        ctx.getSource().sendFeedback(() -> Text.literal("§a[S3Backup] Wizard finished."), true);
                        return 1;
                    }))
                    .then(CommandManager.literal("show").executes(ctx -> {
                        var inline = CredentialsStore.load();
                        String source = (inline != null && inline.accessKeyId != null) ? "Inline secrets file" : "Default AWS provider chain";
                        ctx.getSource().sendFeedback(() -> Text.literal(
                                "§bRegion:         §f" + cfg.awsRegion + "\n" +
                                        "§bBucket:         §f" + cfg.s3Bucket + "\n" +
                                        "§bPrefix:         §f" + cfg.s3Prefix + "\n" +
                                        "§bKeep last:      §f" + cfg.keepLastNS3 + "\n" +
                                        "§bMultipart thr.: §f" + cfg.multipartThresholdMB + " MB\n" +
                                        "§bMultipart part: §f" + cfg.multipartPartSizeMB + " MB\n" +
                                        "§bCreds:          §f" + source), false);
                        return 1;
                    }))
                    .then(CommandManager.literal("set")
                            .then(CommandManager.argument("field", com.mojang.brigadier.arguments.StringArgumentType.word())
                                    .suggests((c,b) -> {
                                        for (var s : java.util.List.of("region","bucket","prefix","keep","multipartThresholdMB","multipartPartSizeMB","accessKey","secretKey","sessionToken")) b.suggest(s);
                                        return b.buildFuture();
                                    })
                                    .then(CommandManager.argument("value", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                            .executes(ctx -> {
                                                String field = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "field");
                                                String value = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "value");
                                                handleSet(ctx.getSource(), field, value);
                                                return 1;
                                            }))))
                    .then(CommandManager.literal("test").executes(ctx -> {
                        var server = ctx.getSource().getServer();
                        try {
                            S3ClientHolder.rebuild(cfg.awsRegion);
                            String prefix = (cfg.s3Prefix == null || cfg.s3Prefix.isBlank()) ? "" : cfg.s3Prefix.replaceAll("^/+", "").replaceAll("/+$","") + "/";
                            String key = prefix + "_s3backup-test-" + System.currentTimeMillis() + ".txt";
                            Path outDir = Paths.get("config", MODID);
                            Files.createDirectories(outDir);
                            Path tmp = Files.createTempFile(outDir, "s3test-", ".txt");
                            Files.writeString(tmp, "it works", StandardCharsets.UTF_8);
                            PutObjectRequest req = PutObjectRequest.builder().bucket(cfg.s3Bucket).key(key).build();
                            S3ClientHolder.client().putObject(req, tmp);
                            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
                            ctx.getSource().sendFeedback(() -> Text.literal("§a[S3Backup] Test uploaded to s3://" + cfg.s3Bucket + "/" + key), false);
                        } catch (Exception e) {
                            ctx.getSource().sendError(Text.literal("§c[S3Backup] Test failed: " + e.getMessage()));
                        }
                        return 1;
                    }));
            dispatcher.register(root);
        });

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            server.getPlayerManager().broadcast(Text.literal("[S3Backup] Scheduled backups every " + cfg.backupIntervalMinutes + " minutes."), false);
        });

        ticksPerBackup = Math.max(1, cfg.backupIntervalMinutes) * 60L * 20L;
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (ticksPerBackup > 0 && ticks.incrementAndGet() % ticksPerBackup == 0) startBackup(server);
        });
    }

    private static Config loadOrCreateConfig() {
        try {
            Path dir = Paths.get("config");
            Files.createDirectories(dir);
            Path path = dir.resolve(MODID + ".json");
            if (Files.exists(path)) return GSON.fromJson(Files.readString(path), Config.class);
            Config c = new Config();
            Files.writeString(path, GSON.toJson(c));
            return c;
        } catch (IOException e) {
            throw new RuntimeException("Failed to load config", e);
        }
    }

    private static void saveConfig(Config c) {
        try {
            var path = Paths.get("config").resolve(MODID + ".json");
            Files.writeString(path, GSON.toJson(c));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void handleSet(ServerCommandSource src, String field, String value) {
        field = field.toLowerCase();
        switch (field) {
            case "interval" -> {
                cfg.backupIntervalMinutes = Integer.parseInt(value.trim());
                saveConfig(cfg);
                src.sendFeedback(() -> Text.literal("§aInterval set."), false);
            }
            case "region" -> {
                cfg.awsRegion = value.trim();
                saveConfig(cfg);
                src.sendFeedback(() -> Text.literal("§aRegion set."), false);
            }
            case "bucket" -> {
                cfg.s3Bucket = value.trim();
                saveConfig(cfg);
                src.sendFeedback(() -> Text.literal("§aBucket set."), false);
            }
            case "prefix" -> {
                cfg.s3Prefix = value.trim();
                saveConfig(cfg);
                src.sendFeedback(() -> Text.literal("§aPrefix set."), false);
            }
            case "keep" -> {
                try {
                    int n = Integer.parseInt(value.trim());
                    cfg.keepLastNS3 = Math.max(0, n);
                    saveConfig(cfg);
                    src.sendFeedback(() -> Text.literal("§aKeep-last set to " + cfg.keepLastNS3 + "."), false);
                } catch (NumberFormatException e) {
                    src.sendError(Text.literal("§cInvalid number for keep."));
                }
            }
            case "multipartthresholdmb" -> {
                try {
                    int mb = Integer.parseInt(value.trim());
                    cfg.multipartThresholdMB = Math.max(5, mb);
                    saveConfig(cfg);
                    src.sendFeedback(() -> Text.literal("§aMultipart threshold set to " + cfg.multipartThresholdMB + " MB."), false);
                } catch (NumberFormatException e) {
                    src.sendError(Text.literal("§cInvalid number for multipartThresholdMB."));
                }
            }
            case "multipartpartsizemb" -> {
                try {
                    int mb = Integer.parseInt(value.trim());
                    cfg.multipartPartSizeMB = Math.max(5, mb);
                    saveConfig(cfg);
                    src.sendFeedback(() -> Text.literal("§aMultipart part size set to " + cfg.multipartPartSizeMB + " MB."), false);
                } catch (NumberFormatException e) {
                    src.sendError(Text.literal("§cInvalid number for multipartPartSizeMB."));
                }
            }
            case "accesskey", "secretkey", "sessiontoken" -> {
                var s = CredentialsStore.load();
                if (s == null) s = new CredentialsStore.Secrets();
                if (field.equals("accesskey")) s.accessKeyId = value.trim();
                if (field.equals("secretkey")) s.secretAccessKey = value.trim();
                if (field.equals("sessiontoken")) s.sessionToken = value.trim();
                CredentialsStore.save(s);
                src.sendFeedback(() -> Text.literal("§aCredential saved."), false);
            }
            default -> src.sendError(Text.literal("§cUnknown field."));
        }
    }

    private static void startBackup(MinecraftServer server) {
        CompletableFuture
                .runAsync(() -> saveWorldSync(server))
                .thenRunAsync(() -> runBackupIO(server), IO)
                .exceptionally(ex -> {
                    server.sendMessage(Text.literal("[S3Backup] Backup failed: " + ex.getMessage()));
                    ex.printStackTrace();
                    return null;
                });
    }

    private static void saveWorldSync(MinecraftServer server) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        server.execute(() -> {
            try {
                server.getCommandManager().executeWithPrefix(server.getCommandSource(), "save-all flush");
            } finally {
                done.complete(null);
            }
        });
        try { done.get(60, TimeUnit.SECONDS); } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static void runBackupIO(MinecraftServer server) {
        try {
            Path levelRoot = server.getSavePath(WorldSavePath.ROOT).toAbsolutePath();
            Path outDir = Paths.get("config", MODID);
            Files.createDirectories(outDir);
            String ts = LocalDateTime.now().toString().replace(':','-');
            String zipName = cfg.zipBaseName + "-" + ts + ".zip";
            Path zipPath = outDir.resolve(zipName);
            ZipUtil.zipDirectory(levelRoot, zipPath, cfg.excludeGlobs);
            String prefix = (cfg.s3Prefix == null) ? "" : cfg.s3Prefix.replaceAll("^/+", "").replaceAll("/+$","");
            String key = prefix.isBlank() ? zipName : prefix + "/" + zipName;

            long thresholdBytes = cfg.multipartThresholdMB * 1024L * 1024L;
            boolean useMultipart = S3Multipart.needsMultipart(zipPath, thresholdBytes);

            if (useMultipart) {
                long partBytes = cfg.multipartPartSizeMB * 1024L * 1024L;
                S3Multipart.upload(S3ClientHolder.client(), zipPath, cfg.s3Bucket, key, partBytes);
            } else {
                PutObjectRequest req = PutObjectRequest.builder().bucket(cfg.s3Bucket).key(key).build();
                S3ClientHolder.client().putObject(req, zipPath);
            }

            server.sendMessage(Text.literal("[S3Backup] Uploaded to s3://" + cfg.s3Bucket + "/" + key));
            pruneOldBackupsS3(cfg.s3Bucket, cfg.s3Prefix, cfg.zipBaseName, cfg.keepLastNS3);

            if (cfg.deleteLocalAfterUpload && !cfg.keepLatestLocal) {
                try { Files.deleteIfExists(zipPath); } catch (IOException ignored) {}
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void pruneOldBackupsS3(String bucket, String prefix, String zipBaseName, int keepN) {
        if (keepN <= 0) return;
        try {
            var s3 = S3ClientHolder.client();
            String normPrefix = (prefix == null) ? "" : prefix.replaceAll("^/+", "").replaceAll("/+$", "");
            String keyPrefix = normPrefix.isBlank() ? "" : (normPrefix + "/");

            List<S3Object> all = new ArrayList<>();
            String token = null;
            do {
                var req = ListObjectsV2Request.builder().bucket(bucket).prefix(keyPrefix).continuationToken(token).build();
                var resp = s3.listObjectsV2(req);
                for (S3Object o : resp.contents()) {
                    String key = o.key();
                    if (key.endsWith(".zip")) {
                        String name = key.substring(key.lastIndexOf('/') + 1);
                        if (name.startsWith(zipBaseName + "-")) all.add(o);
                    }
                }
                token = resp.isTruncated() ? resp.nextContinuationToken() : null;
            } while (token != null);

            all.sort((a, b) -> b.lastModified().compareTo(a.lastModified()));
            if (all.size() > keepN) {
                for (int i = keepN; i < all.size(); i++) {
                    String delKey = all.get(i).key();
                    try {
                        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(delKey).build());
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
    }
}
