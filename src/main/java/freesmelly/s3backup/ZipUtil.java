package freesmelly.s3backup;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class ZipUtil {
    private ZipUtil() {}

    public static void zipDirectory(Path sourceDir, Path zipFile, List<String> excludeGlobs) throws IOException {
        var matchers = (excludeGlobs == null) ? List.<PathMatcher>of()
                : excludeGlobs.stream().map(g -> FileSystems.getDefault().getPathMatcher("glob:" + g)).toList();

        try (var zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            byte[] buf = new byte[8192];
            Files.walk(sourceDir).filter(Files::isRegularFile).forEach(p -> {
                Path rel = sourceDir.relativize(p);
                if (matchers.stream().anyMatch(m -> m.matches(rel))) return;
                try (InputStream in = Files.newInputStream(p)) {
                    zos.putNextEntry(new ZipEntry(rel.toString().replace('\\', '/')));
                    int r;
                    while ((r = in.read(buf)) != -1) {
                        zos.write(buf, 0, r);
                    }
                    zos.closeEntry();
                } catch (IOException ignored) {}
            });
        }
    }
}
