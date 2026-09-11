package io.github.zhumaniezov.codex.edt.tests;

import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.core.runtime.FileLocator;
import org.osgi.framework.FrameworkUtil;
import com.google.gson.Gson;

public final class TestServer {
    private TestServer() { }

    public static List<String> command(String mode) throws Exception {
        return List.of(System.getProperty("java.home") + "/bin/java.exe", "-cp",
            root(FakeAppServer.class) + java.io.File.pathSeparator
                + FileLocator.getBundleFileLocation(FrameworkUtil.getBundle(Gson.class)).orElseThrow().getAbsolutePath(),
            FakeAppServer.class.getName(), mode);
    }

    private static String root(Class<?> type) throws Exception {
        URL url = FileLocator.toFileURL(type.getResource(type.getSimpleName() + ".class"));
        if (url.getProtocol().equals("jar")) {
            return Path.of(((JarURLConnection) url.openConnection()).getJarFileURL().toURI()).toString();
        }
        Path path = Path.of(url.toURI());
        for (String ignored : type.getName().split("\\.")) { path = path.getParent(); }
        return path.toString();
    }
}
