package org.booklore.task.tasks;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.BookLoreUser;
import org.booklore.model.dto.request.TaskCreateRequest;
import org.booklore.model.dto.response.TaskCreateResponse;
import org.booklore.model.dto.settings.AppSettings;
import org.booklore.model.enums.TaskType;
import org.booklore.model.enums.UserPermission;
import org.booklore.repository.BookRepository;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.service.conversion.EbookConversionService;
import org.booklore.task.TaskStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class AutoConvertMissingFormatsTask implements Task {

    private final BookRepository bookRepository;
    private final AppSettingService appSettingService;
    private final EbookConversionService ebookConversionService;

    @Override
    public void validatePermissions(BookLoreUser user, TaskCreateRequest request) {
        if (!UserPermission.CAN_ACCESS_TASK_MANAGER.isGranted(user.getPermissions())) {
            throw ApiError.PERMISSION_DENIED.createException(UserPermission.CAN_ACCESS_TASK_MANAGER);
        }
    }

    @Override
    public TaskCreateResponse execute(TaskCreateRequest request) {
        TaskCreateResponse.TaskCreateResponseBuilder builder = TaskCreateResponse.builder()
                .taskId(UUID.randomUUID().toString())
                .taskType(getTaskType());

        long startTime = System.currentTimeMillis();
        log.info("{}: Task started", getTaskType());

        AppSettings settings = appSettingService.getAppSettings();
        List<String> wantedFormats = EbookConversionService.parseWantedFormats(settings.getAutoConvertFormats());
        if (!settings.isAutoConvertEnabled() || wantedFormats.isEmpty()) {
            log.warn("{}: Auto conversion is disabled or no wanted formats are configured under File Conversion, nothing to do", getTaskType());
            builder.status(TaskStatus.FAILED);
            return builder.build();
        }

        try {
            List<Long> bookIds = bookRepository.findAllActiveBookIds();
            log.info("{}: Checking {} books for missing formats: {}", getTaskType(), bookIds.size(), wantedFormats);

            int processed = 0;
            for (Long bookId : bookIds) {
                try {
                    ebookConversionService.convertMissingFormats(bookId);
                } catch (Exception e) {
                    log.error("{}: Failed to process book {}: {}", getTaskType(), bookId, e.getMessage(), e);
                }
                processed++;
                if (processed % 100 == 0) {
                    log.info("{}: Processed {}/{} books", getTaskType(), processed, bookIds.size());
                }
            }

            log.info("{}: Processed {} books", getTaskType(), processed);
            builder.status(TaskStatus.COMPLETED);
        } catch (Exception e) {
            log.error("{}: Error converting missing formats", getTaskType(), e);
            builder.status(TaskStatus.FAILED);
        }

        long endTime = System.currentTimeMillis();
        log.info("{}: Task completed. Duration: {} ms", getTaskType(), endTime - startTime);

        return builder.build();
    }

    @Override
    public TaskType getTaskType() {
        return TaskType.AUTO_CONVERT_MISSING_FORMATS;
    }

    @Override
    public String getMetadata() {
        AppSettings settings = appSettingService.getAppSettings();
        if (!settings.isAutoConvertEnabled()) {
            return "Auto conversion is disabled";
        }
        List<String> wantedFormats = EbookConversionService.parseWantedFormats(settings.getAutoConvertFormats());
        if (wantedFormats.isEmpty()) {
            return "No wanted formats configured";
        }
        return "Wanted formats: " + String.join(", ", wantedFormats);
    }
}
