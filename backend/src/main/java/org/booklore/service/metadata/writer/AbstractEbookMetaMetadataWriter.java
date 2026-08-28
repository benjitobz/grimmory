package org.booklore.service.metadata.writer;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.booklore.model.MetadataClearFlags;
import org.booklore.model.dto.settings.MetadataPersistenceSettings;
import org.booklore.model.entity.BookMetadataEntity;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.util.FileService;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
public abstract class AbstractEbookMetaMetadataWriter implements MetadataWriter {

    private static final long WRITE_TIMEOUT_MINUTES = 2;

    private final AppSettingService appSettingService;
    private final FileService fileService;

    protected AbstractEbookMetaMetadataWriter(AppSettingService appSettingService, FileService fileService) {
        this.appSettingService = appSettingService;
        this.fileService = fileService;
    }

    protected abstract MetadataPersistenceSettings.FormatSettings getFormatSettings(MetadataPersistenceSettings.SaveToOriginalFile settings);

    @Override
    public void saveMetadataToFile(File file, BookMetadataEntity metadata, String thumbnailUrl, MetadataClearFlags clear) {
        if (!shouldSaveMetadataToFile(file)) {
            return;
        }

        String extension = StringUtils.substringAfterLast(file.getName().toLowerCase(Locale.ROOT), ".");
        if (!file.exists() || !getSupportedBookType().supports(extension)) {
            log.warn("Invalid {} file: {}", getSupportedBookType(), file.getAbsolutePath());
            return;
        }

        Path binary = fileService.findSystemFile("ebook-meta");
        if (binary == null) {
            log.warn("ebook-meta binary not found on PATH, cannot embed metadata into {}", file.getName());
            return;
        }

        Path filePath = file.toPath();
        Path parentDir = filePath.getParent();
        Path backupPath = null;
        Path coverPath = null;

        try {
            backupPath = Files.createTempFile(parentDir, ".metaBackup-", "." + extension);
            Files.copy(filePath, backupPath, StandardCopyOption.REPLACE_EXISTING);

            List<String> args = new ArrayList<>();
            args.add(binary.toAbsolutePath().toString());
            args.add(file.getAbsolutePath());
            appendMetadataArguments(args, metadata, clear);

            if (StringUtils.isNotBlank(thumbnailUrl)) {
                byte[] coverData = loadImage(thumbnailUrl);
                if (coverData != null && coverData.length > 0) {
                    coverPath = Files.createTempFile(".ebookMetaCover-", ".jpg");
                    Files.write(coverPath, coverData);
                    args.add("--cover");
                    args.add(coverPath.toAbsolutePath().toString());
                }
            }

            runEbookMeta(args, file);
            log.info("Successfully embedded metadata into {}: {}", getSupportedBookType(), file.getName());
        } catch (Exception e) {
            log.warn("Failed to write metadata to {} {}: {}", getSupportedBookType(), file.getName(), e.getMessage());
            if (backupPath != null) {
                try {
                    Files.copy(backupPath, filePath, StandardCopyOption.REPLACE_EXISTING);
                    log.info("Restored {} from temp backup after failure", file.getName());
                } catch (IOException ex) {
                    log.error("Failed to restore temp backup for {}: {}", file.getName(), ex.getMessage(), ex);
                }
            }
        } finally {
            deleteQuietly(coverPath);
            deleteQuietly(backupPath);
        }
    }

    @Override
    public boolean shouldSaveMetadataToFile(File file) {
        MetadataPersistenceSettings.SaveToOriginalFile settings = appSettingService.getAppSettings().getMetadataPersistenceSettings().getSaveToOriginalFile();
        MetadataPersistenceSettings.FormatSettings formatSettings = getFormatSettings(settings);
        if (formatSettings == null || !formatSettings.isEnabled()) {
            log.debug("{} metadata writing is disabled. Skipping: {}", getSupportedBookType(), file.getName());
            return false;
        }

        long fileSizeInMb = file.length() / (1024 * 1024);
        if (fileSizeInMb > formatSettings.getMaxFileSizeInMb()) {
            log.info("{} file {} ({} MB) exceeds max size limit ({} MB). Skipping metadata write.", getSupportedBookType(), file.getName(), fileSizeInMb, formatSettings.getMaxFileSizeInMb());
            return false;
        }

        return true;
    }

    private void appendMetadataArguments(List<String> args, BookMetadataEntity metadata, MetadataClearFlags clear) {
        MetadataCopyHelper helper = new MetadataCopyHelper(metadata);

        helper.copyTitle(clear != null && clear.isTitle(), title -> addArgument(args, "--title", title));
        helper.copyAuthors(clear != null && clear.isAuthors(), authors -> {
            if (authors != null && !authors.isEmpty()) {
                addArgument(args, "--authors", authors.stream().filter(StringUtils::isNotBlank).collect(Collectors.joining(" & ")));
            }
        });
        helper.copyDescription(clear != null && clear.isDescription(), description -> addArgument(args, "--comments", description));
        helper.copyPublisher(clear != null && clear.isPublisher(), publisher -> addArgument(args, "--publisher", publisher));
        helper.copyLanguage(clear != null && clear.isLanguage(), language -> addArgument(args, "--language", language));
        helper.copyIsbn13(clear != null && clear.isIsbn13(), isbn -> addArgument(args, "--isbn", isbn));
        helper.copyPublishedDate(clear != null && clear.isPublishedDate(), date -> {
            if (date != null) {
                addArgument(args, "--date", date.toString());
            }
        });
        helper.copyCategories(clear != null && clear.isCategories(), categories -> {
            if (categories != null && !categories.isEmpty()) {
                addArgument(args, "--tags", categories.stream().filter(StringUtils::isNotBlank).sorted().collect(Collectors.joining(",")));
            }
        });

        if (hasValidSeries(metadata, clear)) {
            addArgument(args, "--series", metadata.getSeriesName());
            addArgument(args, "--index", formatSeriesNumber(metadata.getSeriesNumber()));
        }
    }

    private void runEbookMeta(List<String> args, File file) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(args);
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null && output.length() < 4096) {
                output.append(line).append('\n');
            }
        }

        if (!process.waitFor(WRITE_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new IOException("ebook-meta timed out for " + file.getName());
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new IOException("ebook-meta failed with exit code " + exitCode + ": " + output.toString().strip());
        }
    }

    private static void addArgument(List<String> args, String name, String value) {
        if (StringUtils.isNotBlank(value)) {
            args.add(name);
            args.add(value);
        }
    }

    private boolean hasValidSeries(BookMetadataEntity metadata, MetadataClearFlags clear) {
        if (clear != null && (clear.isSeriesName() || clear.isSeriesNumber())) {
            return false;
        }
        return StringUtils.isNotBlank(metadata.getSeriesName())
                && metadata.getSeriesNumber() != null
                && metadata.getSeriesNumber() > 0;
    }

    private String formatSeriesNumber(Float number) {
        if (number == null) {
            return "0";
        }
        if (number % 1 == 0) {
            return String.valueOf(number.intValue());
        }
        return String.format(Locale.US, "%.2f", number).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private byte[] loadImage(String pathOrUrl) {
        try (InputStream stream = pathOrUrl.startsWith("http") ? URI.create(pathOrUrl).toURL().openStream() : new FileInputStream(pathOrUrl)) {
            return stream.readAllBytes();
        } catch (IOException e) {
            log.warn("Failed to load cover image from {}: {}", pathOrUrl, e.getMessage());
            return null;
        }
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Could not delete temp file {}: {}", path, e.getMessage());
        }
    }
}
