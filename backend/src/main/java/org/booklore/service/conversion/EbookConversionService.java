package org.booklore.service.conversion;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.model.dto.settings.AppSettings;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.entity.LibraryPathEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.BookAdditionalFileRepository;
import org.booklore.repository.BookRepository;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.service.file.FileFingerprint;
import org.booklore.service.monitoring.MonitoringRegistrationService;
import org.booklore.util.FileService;
import org.booklore.util.FileUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class EbookConversionService {

    public static final Map<String, BookFileType> SUPPORTED_TARGET_FORMATS = Map.of(
            "epub", BookFileType.EPUB,
            "mobi", BookFileType.MOBI,
            "azw3", BookFileType.AZW3,
            "fb2", BookFileType.FB2,
            "pdf", BookFileType.PDF);

    private static final List<BookFileType> SOURCE_PREFERENCE = List.of(
            BookFileType.EPUB, BookFileType.AZW3, BookFileType.MOBI, BookFileType.FB2, BookFileType.CBX, BookFileType.PDF);
    private static final long CONVERSION_TIMEOUT_MINUTES = 15;
    private static final long BYTES_TO_KB_DIVISOR = 1024;

    private final BookRepository bookRepository;
    private final BookAdditionalFileRepository additionalFileRepository;
    private final AppSettingService appSettingService;
    private final FileService fileService;
    private final MonitoringRegistrationService monitoringRegistrationService;

    public static List<String> parseWantedFormats(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(format -> !format.isEmpty())
                .distinct()
                .toList();
    }

    @Transactional
    public void convertMissingFormats(Long bookId) {
        AppSettings settings = appSettingService.getAppSettings();
        if (!settings.isAutoConvertEnabled()) {
            return;
        }

        List<String> wantedFormats = parseWantedFormats(settings.getAutoConvertFormats());
        if (wantedFormats.isEmpty()) {
            return;
        }

        BookEntity book = bookRepository.findById(bookId).orElse(null);
        if (book == null || Boolean.TRUE.equals(book.getIsPhysical()) || book.getPrimaryBookFile() == null || book.getLibraryPath() == null) {
            return;
        }

        BookFileEntity source = pickSourceFile(book);
        if (source == null) {
            log.debug("Auto-convert: no convertible source format for book {}", bookId);
            return;
        }

        Set<BookFileType> existingTypes = book.getBookFiles().stream()
                .filter(BookFileEntity::isBook)
                .map(BookFileEntity::getBookType)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));

        for (String format : wantedFormats) {
            BookFileType targetType = SUPPORTED_TARGET_FORMATS.get(format);
            if (targetType == null) {
                log.warn("Auto-convert: unsupported target format '{}', skipping", format);
                continue;
            }
            if (existingTypes.contains(targetType)) {
                continue;
            }

            try {
                if (convertAndAttach(book, source, format, targetType)) {
                    existingTypes.add(targetType);
                }
            } catch (Exception e) {
                log.error("Auto-convert: failed to convert book {} to {}: {}", bookId, format, e.getMessage(), e);
            }
        }
    }

    private BookFileEntity pickSourceFile(BookEntity book) {
        List<BookFileEntity> bookFiles = book.getBookFiles().stream()
                .filter(BookFileEntity::isBook)
                .filter(file -> file.getBookType() != null)
                .toList();

        for (BookFileType type : SOURCE_PREFERENCE) {
            for (BookFileEntity file : bookFiles) {
                if (file.getBookType() == type && Files.isRegularFile(file.getFullFilePath())) {
                    return file;
                }
            }
        }
        return null;
    }

    private boolean convertAndAttach(BookEntity book, BookFileEntity source, String extension, BookFileType targetType) throws IOException, InterruptedException {
        Path converterBinary = fileService.findSystemFile("ebook-convert");
        if (converterBinary == null) {
            log.warn("Auto-convert: ebook-convert binary not found on PATH, skipping conversion");
            return false;
        }

        String sourceFileName = source.getFileName();
        int dotIndex = sourceFileName.lastIndexOf('.');
        String baseName = dotIndex > 0 ? sourceFileName.substring(0, dotIndex) : sourceFileName;
        String targetFileName = baseName + "." + extension;

        Path targetPath = source.getFullFilePath().getParent().resolve(targetFileName);
        if (Files.exists(targetPath)) {
            log.info("Auto-convert: target file already exists on disk, skipping: {}", targetPath);
            return false;
        }

        Path tempDir = Files.createTempDirectory("grimmory-convert-");
        Path tempOutput = tempDir.resolve(targetFileName);
        Long libraryId = book.getLibrary() != null ? book.getLibrary().getId() : null;
        boolean monitoringUnregistered = false;

        try {
            runConversion(converterBinary, source.getFullFilePath(), tempOutput);

            if (!Files.isRegularFile(tempOutput) || Files.size(tempOutput) == 0) {
                throw new IOException("ebook-convert finished but produced no output: " + tempOutput);
            }

            String fileHash = FileFingerprint.generateHash(tempOutput);
            if (additionalFileRepository.findByAltFormatCurrentHash(fileHash).isPresent()) {
                log.info("Auto-convert: identical alternative format already exists for book {}, skipping {}", book.getId(), extension);
                return false;
            }

            if (libraryId != null) {
                monitoringRegistrationService.unregisterLibrary(libraryId);
                monitoringUnregistered = true;
            }

            long fileSize = Files.size(tempOutput);
            Files.createDirectories(targetPath.getParent());
            Files.move(tempOutput, targetPath);

            BookFileEntity entity = BookFileEntity.builder()
                    .book(book)
                    .fileName(targetFileName)
                    .fileSubPath(source.getFileSubPath())
                    .isBookFormat(true)
                    .bookType(targetType)
                    .fileSizeKb(fileSize / BYTES_TO_KB_DIVISOR)
                    .initialHash(fileHash)
                    .currentHash(fileHash)
                    .description("Auto-converted from " + source.getBookType())
                    .addedOn(Instant.now())
                    .build();
            BookFileEntity saved = additionalFileRepository.save(entity);
            book.getBookFiles().add(saved);

            log.info("Auto-convert: created {} for book {} at {}", extension, book.getId(), targetPath);
            return true;
        } finally {
            if (monitoringUnregistered && book.getLibrary() != null && book.getLibrary().getLibraryPaths() != null) {
                for (LibraryPathEntity libraryPath : book.getLibrary().getLibraryPaths()) {
                    try {
                        monitoringRegistrationService.registerLibraryPaths(libraryId, FileUtils.normalizeAbsolutePath(Path.of(libraryPath.getPath())));
                    } catch (Exception e) {
                        log.warn("Auto-convert: failed to re-register library {} for monitoring: {}", libraryId, e.getMessage());
                    }
                }
            }
            deleteQuietly(tempOutput);
            deleteQuietly(tempDir);
        }
    }

    private void runConversion(Path converterBinary, Path input, Path output) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(
                converterBinary.toAbsolutePath().toString(),
                input.toAbsolutePath().toString(),
                output.toAbsolutePath().toString());
        processBuilder.redirectErrorStream(true);

        log.info("Auto-convert: running ebook-convert {} -> {}", input, output);
        Process process = processBuilder.start();

        StringBuilder processOutput = new StringBuilder();
        Thread outputReader = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    processOutput.append(line).append('\n');
                }
            } catch (IOException e) {
                log.debug("Auto-convert: error reading ebook-convert output: {}", e.getMessage());
            }
        });
        outputReader.setDaemon(true);
        outputReader.start();

        if (!process.waitFor(CONVERSION_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new IOException("ebook-convert timed out after " + CONVERSION_TIMEOUT_MINUTES + " minutes");
        }
        outputReader.join(TimeUnit.SECONDS.toMillis(10));

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new IOException("ebook-convert failed with exit code " + exitCode + ": " + tail(processOutput.toString()));
        }
    }

    private static String tail(String output) {
        if (output == null || output.isBlank()) {
            return "(no output)";
        }
        String trimmed = output.strip();
        int maxLength = 500;
        return trimmed.length() <= maxLength ? trimmed : "..." + trimmed.substring(trimmed.length() - maxLength);
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.debug("Auto-convert: failed to clean up temp path {}: {}", path, e.getMessage());
        }
    }
}
