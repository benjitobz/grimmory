package org.booklore.model.enums;

import lombok.Getter;

@Getter
public enum ShelfType {
    KOBO(koboShelfName(), "tablet");

    private final String name;
    private final String icon;

    ShelfType(String name, String icon) {
        this.name = name;
        this.icon = icon;
    }

    private static String koboShelfName() {
        String configured = System.getenv("KOBO_SHELF_NAME");
        return configured == null || configured.isBlank() ? "Kobo" : configured.trim();
    }
}
