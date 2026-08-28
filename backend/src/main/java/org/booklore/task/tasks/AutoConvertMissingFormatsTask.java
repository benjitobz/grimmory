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
import org.booklore.model.websocket.TaskProgressPayload;
import org.booklore.model.websocket.Topic;
import org.booklore.repository.BookRepository;
import org.booklore.service.NotificationService;
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

    private static final long MIN_NOTIFICATION_INTERVAL_MS = 250;

    private final BookRepository bookRepository;
    private final AppSettingService appSettingService;
    private final EbookConversionService ebookConversionService;
    private final NotificationService notificationService;

    @Override
    public void validatePermissions(BookLoreUser user, TaskCreateRequest request) {
        if (!UserPermission.CAN_ACCESS_TASK_MANAGER.isGranted(user.getPermissions())) {
            throw ApiError.PERMISSION_DENIED.createException(UserPermission.CAN_ACCESS_TASK_MANAGER);
        }
    }

    @Override
    public TaskCreateResponse execute(TaskCreateRequest request) {
        String taskId = request.getTaskId() != null ? request.getTaskId() : UUID.randomUUID().toString();
        TaskCreateResponse.TaskCreateResponseBuilder builder = TaskCreateResponse.builder()
                .taskId(taskId)
                .taskType(getTaskType());

        long startTime = System.currentTimeMillis();
        log.info("{}: Task started", getTaskType());

        AppSettings settings = appSettingService.getAppSettings();
        List<String> wantedFormats = EbookConversionService.parseWantedFormats(settings.getAutoConvertFormats());
        if (!settings.isAutoConvertEnabled() || wantedFormats.isEmpty()) {
            log.warn("{}: Auto conversion is disabled or no wanted formats are configured under File Conversion, nothing to do", getTaskType());
            sendProgress(taskId, 100, "Auto conversion is disabled or no wanted formats are configured", TaskStatus.COMPLETED, 0, true);
            builder.status(TaskStatus.COMPLETED);
            return builder.build();
        }

        try {
            List<Long> bookIds = bookRepository.findAllActiveBookIds();
            int totalBooks = bookIds.size();
            log.info("{}: Checking {} books for missing formats: {}", getTaskType(), totalBooks, wantedFormats);

            long lastNotificationTime = sendProgress(taskId, 0,
                    String.format("Checking %d books for missing formats", totalBooks), TaskStatus.IN_PROGRESS, 0, true);

            int processed = 0;
            for (Long bookId : bookIds) {
                try {
                    ebookConversionService.convertMissingFormats(bookId, wantedFormats);
                } catch (Exception e) {
                    log.error("{}: Failed to process book {}: {}", getTaskType(), bookId, e.getMessage(), e);
                }
                processed++;
                int progress = totalBooks > 0 ? (processed * 100) / totalBooks : 100;
                lastNotificationTime = sendProgress(taskId, progress,
                        String.format("Checked %d of %d books", processed, totalBooks), TaskStatus.IN_PROGRESS, lastNotificationTime, false);
                if (processed % 100 == 0) {
                    log.info("{}: Processed {}/{} books", getTaskType(), processed, totalBooks);
                }
            }

            log.info("{}: Processed {} books", getTaskType(), processed);
            sendProgress(taskId, 100, String.format("Checked %d books for missing formats", processed), TaskStatus.COMPLETED, 0, true);
            builder.status(TaskStatus.COMPLETED);
        } catch (Exception e) {
            log.error("{}: Error converting missing formats", getTaskType(), e);
            sendProgress(taskId, 100, "Conversion task failed: " + e.getMessage(), TaskStatus.FAILED, 0, true);
            builder.status(TaskStatus.FAILED);
        }

        long endTime = System.currentTimeMillis();
        log.info("{}: Task completed. Duration: {} ms", getTaskType(), endTime - startTime);

        return builder.build();
    }

    private long sendProgress(String taskId, int progress, String message, TaskStatus taskStatus, long lastNotificationTime, boolean force) {
        long currentTime = System.currentTimeMillis();
        if (!force && (currentTime - lastNotificationTime) < MIN_NOTIFICATION_INTERVAL_MS) {
            return lastNotificationTime;
        }
        try {
            TaskProgressPayload payload = TaskProgressPayload.builder()
                    .taskId(taskId)
                    .taskType(getTaskType())
                    .message(message)
                    .progress(progress)
                    .taskStatus(taskStatus)
                    .build();
            notificationService.sendMessage(Topic.TASK_PROGRESS, payload);
            log.info("{}: sent progress {}% ({}) for taskId={}", getTaskType(), progress, taskStatus, taskId);
        } catch (Exception e) {
            log.error("Failed to send task progress notification for taskId={}: {}", taskId, e.getMessage(), e);
        }
        return currentTime;
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
