package org.booklore.model.dto.settings;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class MetadataPersistenceSettings {
    private SaveToOriginalFile saveToOriginalFile;
    private boolean convertCbrCb7ToCbz;
    private boolean moveFilesToLibraryPattern;
    private SidecarSettings sidecarSettings;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SaveToOriginalFile {
        private FormatSettings epub;
        private FormatSettings pdf;
        private FormatSettings cbx;
        private FormatSettings audiobook;
        private FormatSettings mobi;
        private FormatSettings azw3;
        private FormatSettings fb2;

        public boolean isAnyFormatEnabled() {
            return (epub != null && epub.isEnabled())
                    || (pdf != null && pdf.isEnabled())
                    || (cbx != null && cbx.isEnabled())
                    || (audiobook != null && audiobook.isEnabled())
                    || (mobi != null && mobi.isEnabled())
                    || (azw3 != null && azw3.isEnabled())
                    || (fb2 != null && fb2.isEnabled());
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FormatSettings {
        private boolean enabled;
        private int maxFileSizeInMb;
    }
}
