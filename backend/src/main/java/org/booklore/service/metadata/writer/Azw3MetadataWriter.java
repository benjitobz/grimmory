package org.booklore.service.metadata.writer;

import org.booklore.model.dto.settings.MetadataPersistenceSettings;
import org.booklore.model.enums.BookFileType;
import org.booklore.service.appsettings.AppSettingService;
import org.booklore.util.FileService;
import org.springframework.stereotype.Component;

@Component
public class Azw3MetadataWriter extends AbstractEbookMetaMetadataWriter {

    public Azw3MetadataWriter(AppSettingService appSettingService, FileService fileService) {
        super(appSettingService, fileService);
    }

    @Override
    protected MetadataPersistenceSettings.FormatSettings getFormatSettings(MetadataPersistenceSettings.SaveToOriginalFile settings) {
        return settings.getAzw3();
    }

    @Override
    public BookFileType getSupportedBookType() {
        return BookFileType.AZW3;
    }
}
