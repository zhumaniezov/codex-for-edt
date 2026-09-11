package io.github.zhumaniezov.codex.edt.ui.presentation;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import io.github.zhumaniezov.codex.edt.settings.LocalizationService;

public final class AttachmentSelection {
    public record Item(Path project, Path file, String relative) {
    }

    private final List<Item> items = new ArrayList<>();

    public static Item validate(Path project, Path file) throws IOException {
        Path root = project.toRealPath(), target = file.toRealPath();
        if (!target.startsWith(root) || !Files.isRegularFile(target)) {
            throw new IOException(LocalizationService.tr("attachmentOutside"));
        }
        return new Item(root, target, root.relativize(target).toString().replace('\\', '/'));
    }

    public synchronized void add(Item item) {
        if (items.contains(item)) {
            return;
        }
        if (items.size() >= 5) {
            throw new IllegalStateException(LocalizationService.tr("attachmentLimit"));
        }
        if (!items.isEmpty() && !items.get(0).project().equals(item.project())) {
            throw new IllegalStateException(LocalizationService.tr("attachmentProject"));
        }
        items.add(item);
    }

    public synchronized List<Item> items() {
        return List.copyOf(items);
    }

    public synchronized void remove(Item item) {
        items.remove(item);
    }

    public synchronized void clear() {
        items.clear();
    }

    public synchronized List<String> references(Path project) throws IOException {
        var result = new ArrayList<String>();
        Path root = project.toRealPath();
        for (var item : items) {
            if (!root.equals(item.project())) {
                throw new IOException(LocalizationService.tr("attachmentProject"));
            }
            result.add(validate(root, item.file()).relative());
        }
        return List.copyOf(result);
    }
}
