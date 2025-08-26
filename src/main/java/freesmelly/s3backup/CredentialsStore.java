package freesmelly.s3backup;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;

final class CredentialsStore {
    static final String FILENAME = "s3-backup-mod.secrets.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    static final class Secrets {
        String accessKeyId;
        String secretAccessKey;
        String sessionToken; // optional
    }

    static Path path() {
        return Paths.get("config").resolve(FILENAME);
    }

    static Secrets load() {
        try {
            Path p = path();
            if (!Files.exists(p)) return null;
            return GSON.fromJson(Files.readString(p), Secrets.class);
        } catch (IOException e) {
            return null;
        }
    }

    static void save(Secrets s) {
        try {
            Path dir = Paths.get("config");
            Files.createDirectories(dir);
            Path p = path();
            Files.writeString(p, GSON.toJson(s), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            var view = Files.getFileAttributeView(p, PosixFileAttributeView.class);
            if (view != null) {
                Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-------"));
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to save secrets", e);
        }
    }
}
