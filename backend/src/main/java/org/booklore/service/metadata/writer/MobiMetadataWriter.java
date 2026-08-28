package org.booklore.service.metadata.writer;

import org.booklore.model.dto.settings.MetadataPersistenceSettings;
import org.booklore.model.enums.BookFileType;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.util.FileService;
import org.springframework.stereotype.Component;

@Component
public class MobiMetadataWriter extends AbstractEbookMetaMetadataWriter {

    public MobiMetadataWriter(AppSettingService appSettingService, FileService fileService) {
        super(appSettingService, fileService);
    }

    @Override
    protected MetadataPersistenceSettings.FormatSettings getFormatSettings(MetadataPersistenceSettings.SaveToOriginalFile settings) {
        return settings.getMobi();
    }

    @Override
    public BookFileType getSupportedBookType() {
        return BookFileType.MOBI;
    }
}
