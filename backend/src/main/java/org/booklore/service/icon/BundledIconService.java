package org.booklore.service.icon;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.util.FileService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Custom SVG icons this fork ships with. They are copied into the icons folder
 * of the data directory on startup when they are not already there, so a shelf
 * that names one always has a file behind it, on a fresh deployment too. An
 * icon a user has edited or replaced is never overwritten.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BundledIconService {

    public static final String KOBO_ICON = "kobo-white";
    public static final String KOBO_ICON_ALTERNATE = "kobo-red";
    public static final String KOREADER_ICON = "koreader-icon";
    public static final String AUDIOBOOKSHELF_ICON = "audiobookshelf-logo";

    static final List<String> BUNDLED_ICONS = List.of(
            KOBO_ICON, KOBO_ICON_ALTERNATE, KOREADER_ICON, AUDIOBOOKSHELF_ICON);

    private static final String CLASSPATH_DIR = "bundled-icons/";
    private static final String SVG_EXTENSION = ".svg";

    private final FileService fileService;

    @EventListener(ApplicationReadyEvent.class)
    @Order(0)
    public void seedBundledIcons() {
        Path iconDir = Path.of(fileService.getIconsSvgFolder());
        try {
            Files.createDirectories(iconDir);
        } catch (Exception e) {
            log.warn("Could not create the icons folder {}; bundled icons were not installed", iconDir, e);
            return;
        }

        int installed = 0;
        for (String icon : BUNDLED_ICONS) {
            if (copyIfMissing(icon, iconDir)) {
                installed++;
            }
        }
        log.info("Bundled icons checked in {} ({} installed)", iconDir, installed);
    }

    private boolean copyIfMissing(String icon, Path iconDir) {
        Path target = iconDir.resolve(icon + SVG_EXTENSION);
        if (Files.exists(target)) {
            return false;
        }
        try (InputStream source = new ClassPathResource(CLASSPATH_DIR + icon + SVG_EXTENSION).getInputStream()) {
            Files.copy(source, target);
            return true;
        } catch (Exception e) {
            log.warn("Could not install the bundled icon '{}'", icon, e);
            return false;
        }
    }
}
