package io.github.zhumaniezov.codex.edt.ui;

import static io.github.zhumaniezov.codex.edt.settings.LocalizationService.tr;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.eclipse.compare.*;
import org.eclipse.compare.patch.*;
import org.eclipse.compare.structuremergeviewer.*;
import org.eclipse.core.runtime.*;
import org.eclipse.swt.graphics.Image;

/**
 * Compare работает только с байтами серверного diff. Запись на диск
 * отсутствует.
 */
public final class DiffReviewService {
    private DiffReviewService() {
    }

    public record Summary(int files, int added, int deleted) {
    }

    public static Summary summary(String diff) throws CoreException {
        var patches = PatchParser.parsePatch(new ReaderCreator() {
            public Reader createReader() {
                return new StringReader(diff);
            }
        });
        int added = 0, deleted = 0;
        for (var p : patches) {
            for (var h : p.getHunks()) {
                for (var line : h.getUnifiedLines()) {
                    if (line.startsWith("+")) {
                        added++;
                    } else if (line.startsWith("-")) {
                        deleted++;
                    }
                }
            }
        }
        return new Summary(patches.length, added, deleted);
    }

    public static DiffNode parse(Map<String, String> diffs) throws Exception {
        var root = new DiffNode(Differencer.CHANGE);
        for (var entry : diffs.entrySet()) {
            String diff = entry.getValue();
            if (diff.startsWith("@@")) {
                diff = "--- a/" + entry.getKey() + "\n+++ b/" + entry.getKey() + "\n" + diff;
            }
            final String source = diff;
            var patches = PatchParser.parsePatch(new ReaderCreator() {
                @Override
                public Reader createReader() {
                    return new StringReader(source);
                }
            });
            for (var patch : patches) {
                String file = patch.getTargetPath(new PatchConfiguration()).toPortableString();
                for (var hunk : patch.getHunks()) {
                    String label = file + " · " + hunk.getLabel();
                    try (var before = hunk.getOriginalContents(); var after = hunk.getPatchedContents()) {
                        new DiffNode(root, Differencer.CHANGE, null, new Content(label, before.readAllBytes()),
                                new Content(label, after.readAllBytes()));
                    }
                }
            }
            if (patches.length == 0) {
                new DiffNode(root, Differencer.CHANGE, null, new Content(entry.getKey(), new byte[0]),
                        new Content(entry.getKey(), source.getBytes(StandardCharsets.UTF_8)));
            }
        }
        return root;
    }

    public static void open(Map<String, String> diffs) {
        open(diffs, "");
    }

    public static DiffNode file(String path, String kind, String diff) throws Exception {
        if (!kind.equals("add") && !kind.equals("delete")) {
            return parse(Map.of(path, diff));
        }
        var root = new DiffNode(Differencer.CHANGE);
        var contents = diff.getBytes(StandardCharsets.UTF_8);
        new DiffNode(root, Differencer.CHANGE, null, new Content(path, kind.equals("delete") ? contents : new byte[0]),
                new Content(path, kind.equals("add") ? contents : new byte[0]));
        return root;
    }

    public static void open(Map<String, String> diffs, String kind) {
        var config = new CompareConfiguration();
        config.setLeftEditable(false);
        config.setRightEditable(false);
        config.setLeftLabel(tr("diffBefore"));
        config.setRightLabel(tr("diffAfter"));
        var copy = Map.copyOf(diffs);
        CompareUI.openCompareEditor(new CompareEditorInput(config) {
            {
                setTitle(tr("agentReview"));
            }

            @Override
            protected Object prepareInput(IProgressMonitor monitor) throws java.lang.reflect.InvocationTargetException {
                try {
                    if (!kind.isBlank() && copy.size() == 1) {
                        var entry = copy.entrySet().iterator().next();
                        return file(entry.getKey(), kind, entry.getValue());
                    }
                    return parse(copy);
                } catch (Exception error) {
                    throw new java.lang.reflect.InvocationTargetException(error);
                }
            }
        });
    }

    private record Content(String name, byte[] bytes) implements ITypedElement, IEncodedStreamContentAccessor {
        public String getName() {
            return name;
        }

        public String getType() {
            return ITypedElement.TEXT_TYPE;
        }

        public Image getImage() {
            return null;
        }

        public InputStream getContents() {
            return new ByteArrayInputStream(bytes);
        }

        public String getCharset() {
            return StandardCharsets.UTF_8.name();
        }
    }
}
