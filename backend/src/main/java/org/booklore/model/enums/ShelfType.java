package org.booklore.model.enums;

import lombok.Getter;

@Getter
public enum ShelfType {
    KOBO(koboShelfName(), "kobo-white", IconType.CUSTOM_SVG);

    private final String name;
    private final String icon;
    private final IconType iconType;

    ShelfType(String name, String icon, IconType iconType) {
        this.name = name;
        this.icon = icon;
        this.iconType = iconType;
    }

    private static String koboShelfName() {
        String configured = System.getenv("KOBO_SHELF_NAME");
        return configured == null || configured.isBlank() ? "Kobo" : configured.trim();
    }
}
