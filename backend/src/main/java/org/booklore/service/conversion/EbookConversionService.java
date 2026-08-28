package org.booklore.service.conversion;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.booklore.model.dto.settings.AppSettings;
import org.booklore.model.entity.BookEntity;
import org.booklore.model.entity.BookFileEntity;
import org.booklore.model.enums.BookFileType;
import org.booklore.repository.BookAdditionalFileRepository;
import org.booklore.repository.BookRepository;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.service.file.FileFingerprint;
import org.booklore.service.monitoring.MonitoringRegistrationService;
import org.booklore.util.FileService;
import org.booklore.util.FileUtils;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
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
    private static final int PROCESS_OUTPUT_MAX_CHARS = 8192;

    private final BookRepository bookRepository;
    private final BookAdditionalFileRepository additionalFileRepository;
    private final AppSettingService appSettingService;
    private final FileService fileService;
    private final MonitoringRegistrationService monitoringRegistrationService;
    private final TransactionTemplate transactionTemplate;

    public EbookConversionService(BookRepository bookRepository,
                                  BookAdditionalFileRepository additionalFileRepository,
                                  AppSettingService appSettingService,
                                  FileService fileService,
                                  MonitoringRegistrationService monitoringRegistrationService,
                                  PlatformTransactionManager transactionManager) {
        this.bookRepository = bookRepository;
        this.additionalFileRepository = additionalFileRepository;
        this.appSettingService = appSettingService;
        this.fileService = fileService;
        this.monitoringRegistrationService = monitoringRegistrationService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

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

    public void convertMissingFormats(Long bookId) {
        AppSettings settings = appSettingService.getAppSettings();
        if (!settings.isAutoConvertEnabled()) {
            return;
        }
        convertMissingFormats(bookId, parseWantedFormats(settings.getAutoConvertFormats()));
    }

    public void convertMissingFormats(Long bookId, List<String> wantedFormats) {
        if (wantedFormats.isEmpty()) {
            return;
        }

        ConversionPlan plan = transactionTemplate.execute(status -> buildPlan(bookId, wantedFormats));
        if (plan == null || plan.targets().isEmpty()) {
            return;
        }

        Path converterBinary = fileService.findSystemFile("ebook-convert");
        if (converterBinary == null) {
            log.warn("Auto-convert: ebook-convert binary not found on PATH, skipping conversion");
            return;
        }

        boolean monitoringPaused = false;
        try {
            for (TargetFormat target : plan.targets()) {
                Path tempDir = null;
                try {
                    Path targetPath = plan.sourceDirectory().resolve(plan.baseName() + "." + target.extension());
                    if (Files.exists(targetPath)) {
                        log.info("Auto-convert: target file already exists on disk, skipping: {}", targetPath);
                        continue;
                    }

                    tempDir = Files.createTempDirectory("grimmory-convert-");
                    Path tempOutput = tempDir.resolve(targetPath.getFileName().toString());
                    runConversion(converterBinary, plan.sourcePath(), tempOutput);

                    if (!Files.isRegularFile(tempOutput)) {
                        throw new IOException("ebook-convert finished but produced no output: " + tempOutput);
                    }
                    long fileSize = Files.size(tempOutput);
                    if (fileSize == 0) {
                        throw new IOException("ebook-convert produced an empty output file: " + tempOutput);
                    }

                    String fileHash = FileFingerprint.generateHash(tempOutput);
                    if (hasExistingFileWithHash(fileHash)) {
                        log.info("Auto-convert: identical alternative format already exists for book {}, skipping {}", plan.bookId(), target.extension());
                        continue;
                    }

                    if (!monitoringPaused && plan.libraryId() != null) {
                        monitoringRegistrationService.unregisterLibrary(plan.libraryId());
                        monitoringPaused = true;
                    }

                    Files.createDirectories(targetPath.getParent());
                    Files.move(tempOutput, targetPath);

                    transactionTemplate.executeWithoutResult(status ->
                            attachConvertedFile(plan, target, targetPath.getFileName().toString(), fileSize, fileHash));
                    log.info("Auto-convert: created {} for book {} at {}", target.extension(), plan.bookId(), targetPath);
                } catch (Exception e) {
                    log.error("Auto-convert: failed to convert book {} to {}: {}", bookId, target.extension(), e.getMessage(), e);
                } finally {
                    cleanupTempDirectory(tempDir);
                }
            }
        } finally {
            if (monitoringPaused) {
                for (Path libraryRoot : plan.libraryRoots()) {
                    try {
                        monitoringRegistrationService.registerLibraryPaths(plan.libraryId(), libraryRoot);
                    } catch (Exception e) {
                        log.warn("Auto-convert: failed to re-register library {} for monitoring: {}", plan.libraryId(), e.getMessage());
                    }
                }
            }
        }
    }

    private ConversionPlan buildPlan(Long bookId, List<String> wantedFormats) {
        BookEntity book = bookRepository.findById(bookId).orElse(null);
        if (book == null || Boolean.TRUE.equals(book.getIsPhysical()) || book.getPrimaryBookFile() == null || book.getLibraryPath() == null) {
            return null;
        }

        List<BookFileEntity> bookFiles = book.getBookFiles().stream()
                .filter(BookFileEntity::isBook)
                .filter(file -> file.getBookType() != null)
                .toList();

        BookFileEntity source = pickSourceFile(bookFiles);
        if (source == null) {
            log.debug("Auto-convert: no convertible source format for book {}", bookId);
            return null;
        }

        Set<BookFileType> existingTypes = new HashSet<>();
        for (BookFileEntity file : bookFiles) {
            existingTypes.add(file.getBookType());
        }

        List<TargetFormat> targets = new ArrayList<>();
        for (String format : wantedFormats) {
            BookFileType targetType = SUPPORTED_TARGET_FORMATS.get(format);
            if (targetType == null) {
                log.warn("Auto-convert: unsupported target format '{}', skipping", format);
                continue;
            }
            if (!existingTypes.contains(targetType)) {
                targets.add(new TargetFormat(format, targetType));
            }
        }
        if (targets.isEmpty()) {
            return null;
        }

        Long libraryId = book.getLibrary() != null ? book.getLibrary().getId() : null;
        List<Path> libraryRoots = book.getLibrary() != null && book.getLibrary().getLibraryPaths() != null
                ? book.getLibrary().getLibraryPaths().stream()
                        .map(libraryPath -> FileUtils.normalizeAbsolutePath(Path.of(libraryPath.getPath())))
                        .toList()
                : List.<Path>of();

        Path sourcePath = source.getFullFilePath();
        return new ConversionPlan(
                bookId,
                libraryId,
                libraryRoots,
                sourcePath,
                sourcePath.getParent(),
                source.getFileSubPath(),
                FilenameUtils.getBaseName(source.getFileName()),
                source.getBookType(),
                targets);
    }

    private BookFileEntity pickSourceFile(List<BookFileEntity> bookFiles) {
        for (BookFileType type : SOURCE_PREFERENCE) {
            for (BookFileEntity file : bookFiles) {
                if (file.getBookType() == type && Files.isRegularFile(file.getFullFilePath())) {
                    return file;
                }
            }
        }
        return null;
    }

    private boolean hasExistingFileWithHash(String fileHash) {
        try {
            return additionalFileRepository.findByAltFormatCurrentHash(fileHash).isPresent();
        } catch (IncorrectResultSizeDataAccessException e) {
            return true;
        }
    }

    private void attachConvertedFile(ConversionPlan plan, TargetFormat target, String fileName, long fileSize, String fileHash) {
        BookEntity book = bookRepository.findById(plan.bookId()).orElse(null);
        if (book == null) {
            return;
        }
        boolean alreadyHasType = book.getBookFiles().stream()
                .filter(BookFileEntity::isBook)
                .anyMatch(file -> file.getBookType() == target.type());
        if (alreadyHasType) {
            return;
        }

        BookFileEntity entity = BookFileEntity.builder()
                .book(book)
                .fileName(fileName)
                .fileSubPath(plan.sourceSubPath())
                .isBookFormat(true)
                .bookType(target.type())
                .fileSizeKb(fileSize / 1024)
                .initialHash(fileHash)
                .currentHash(fileHash)
                .description("Auto-converted from " + plan.sourceType())
                .addedOn(Instant.now())
                .build();
        book.getBookFiles().add(additionalFileRepository.save(entity));
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
                    synchronized (processOutput) {
                        processOutput.append(line).append('\n');
                        if (processOutput.length() > PROCESS_OUTPUT_MAX_CHARS) {
                            processOutput.delete(0, processOutput.length() - PROCESS_OUTPUT_MAX_CHARS);
                        }
                    }
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
            String outputTail;
            synchronized (processOutput) {
                outputTail = tail(processOutput.toString());
            }
            throw new IOException("ebook-convert failed with exit code " + exitCode + ": " + outputTail);
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

    private void cleanupTempDirectory(Path tempDir) {
        if (tempDir == null) {
            return;
        }
        try {
            FileUtils.deleteDirectoryRecursively(tempDir);
        } catch (IOException e) {
            log.debug("Auto-convert: failed to clean up temp directory {}: {}", tempDir, e.getMessage());
        }
    }

    private record TargetFormat(String extension, BookFileType type) {
    }

    private record ConversionPlan(long bookId,
                                  Long libraryId,
                                  List<Path> libraryRoots,
                                  Path sourcePath,
                                  Path sourceDirectory,
                                  String sourceSubPath,
                                  String baseName,
                                  BookFileType sourceType,
                                  List<TargetFormat> targets) {
    }
}
