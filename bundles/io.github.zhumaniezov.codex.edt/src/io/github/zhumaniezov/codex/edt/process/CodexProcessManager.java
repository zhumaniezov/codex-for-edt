package io.github.zhumaniezov.codex.edt.process;

import static io.github.zhumaniezov.codex.edt.settings.LocalizationService.tr;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class CodexProcessManager implements AutoCloseable {
    private final Process process;
    private final BufferedWriter writer;
    private final AtomicBoolean closing = new AtomicBoolean();
    private final CompletableFuture<Void> terminated = new CompletableFuture<>();
    private final ConcurrentHashMap<Long, ProcessHandle> children = new ConcurrentHashMap<>();
    private final java.util.concurrent.ScheduledExecutorService watcher =
        Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "codex-edt-process-watch"));

    public CodexProcessManager(List<String> command, Path directory) throws IOException {
        var builder = new ProcessBuilder(command);
        if (directory != null) { builder.directory(directory.toFile()); }
        process = builder.start();
        writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        watcher.scheduleWithFixedDelay(this::rememberChildren, 0, 200, TimeUnit.MILLISECONDS);
    }

    public void read(Consumer<String> stdout, Consumer<String> stderr, Consumer<Throwable> disconnected) {
        daemon(() -> {
            try {
                readLines(process.getInputStream(), stdout);
                if (!closing.get()) { disconnected.accept(new IOException(tr("text084"))); }
            } catch (Exception error) {
                if (!closing.get()) { disconnected.accept(error); }
            }
        }, "codex-edt-stdout").start();
        daemon(() -> {
            try { readLines(process.getErrorStream(), stderr); }
            catch (Exception error) {
                if (!closing.get()) { stderr.accept(tr("text085")); }
            }
        }, "codex-edt-stderr").start();
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    private static void readLines(InputStream input, Consumer<String> consumer) throws IOException {
        try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            StringBuilder line = new StringBuilder();
            int value;
            while ((value = reader.read()) != -1) {
                if (value == '\n') {
                    if (!line.isEmpty() && line.charAt(line.length() - 1) == '\r') {
                        line.setLength(line.length() - 1);
                    }
                    if (!line.isEmpty()) { consumer.accept(line.toString()); }
                    line.setLength(0);
                } else {
                    if (line.length() >= 2 * 1024 * 1024) { throw new IOException(tr("text086")); }
                    line.append((char) value);
                }
            }
            if (!line.isEmpty()) { throw new IOException(tr("text087")); }
        }
    }

    public synchronized void write(String line) throws IOException {
        if (closing.get() || !process.isAlive()) { throw new IOException(tr("text088")); }
        writer.write(line);
        writer.newLine();
        writer.flush();
    }

    private void rememberChildren() {
        process.descendants().forEach(child -> children.put(child.pid(), child));
    }

    public long pid() { return process.pid(); }
    public boolean isAlive() { return process.isAlive(); }
    public CompletableFuture<Void> termination() { return terminated; }

    @Override
    public void close() {
        if (!closing.compareAndSet(false, true)) { return; }
        // Ограниченное завершение не блокирует SWT; поток живёт до уборки своих процессов.
        Thread cleanup = new Thread(() -> {
            try {
                rememberChildren();
                // Закрытие pipe не должно задержать остановку, если сервер перестал читать STDIN.
                daemon(() -> {
                    try { process.getOutputStream().close(); }
                    catch (IOException ignored) { }
                }, "codex-edt-stdin-close").start();
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    rememberChildren();
                    process.destroy();
                    if (!process.waitFor(2, TimeUnit.SECONDS)) { process.destroyForcibly(); }
                }
            } catch (Exception error) {
                process.destroyForcibly();
            } finally {
                rememberChildren();
                children.values().stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                for (ProcessHandle child : children.values()) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining > 0) {
                        try { child.onExit().get(remaining, TimeUnit.NANOSECONDS); }
                        catch (Exception ignored) { }
                    }
                }
                watcher.shutdownNow();
                try {
                    process.waitFor(2, TimeUnit.SECONDS);
                    process.getInputStream().close();
                    process.getErrorStream().close();
                } catch (Exception ignored) { }
                terminated.complete(null);
            }
        }, "codex-edt-process-stop");
        cleanup.setDaemon(false);
        cleanup.start();
    }
}
