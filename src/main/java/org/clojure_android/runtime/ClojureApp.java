package org.clojure_android.runtime;

import android.app.Application;
import android.util.Log;

/**
 * Application base class that initializes the Clojure runtime on Android.
 *
 * <p>Subclass this in your app's Application class (or use it directly in
 * AndroidManifest.xml) to ensure the Clojure runtime is initialized before
 * any Activity starts.</p>
 *
 * <p>Clojure initialization is triggered lazily by the JVM the first time any
 * Clojure class is referenced. This class ensures that happens early (in
 * {@code onCreate}) on a thread with adequate stack size, rather than on the
 * main thread where a deep Clojure init stack could overflow the default
 * Android main-thread stack.</p>
 */
public class ClojureApp extends Application {

    private static final String TAG = "ClojureApp";
    private static Application instance;

    /**
     * Returns the Application instance. Available after {@code onCreate}.
     */
    public static Application getInstance() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        autoStartNrepl();
    }

    /**
     * Attempts to start the nREPL server in debug builds. In release builds,
     * {@code AndroidDynamicClassLoader} is stripped by the Gradle plugin, so
     * the {@code ClassNotFoundException} is caught and auto-start is silently
     * skipped.
     */
    private void autoStartNrepl() {
        Log.d(TAG, "autoStartNrepl: probing for AndroidDynamicClassLoader");
        try {
            Class<?> adcl = Class.forName("clojure.lang.AndroidDynamicClassLoader");
            Log.d(TAG, "autoStartNrepl: found " + adcl.getName()
                    + " (loader=" + adcl.getClassLoader() + ")");
        } catch (ClassNotFoundException e) {
            Log.d(TAG, "autoStartNrepl: AndroidDynamicClassLoader not found — release build, skipping");
            return;
        }

        Log.i(TAG, "autoStartNrepl: debug build detected, spawning nREPL starter thread");
        Thread starter = new Thread(
            Thread.currentThread().getThreadGroup(),
            () -> {
                try {
                    Log.d(TAG, "nREPL-autostart: resolving clojure.core/require");
                    Object require = clojure.java.api.Clojure.var("clojure.core", "require");

                    Log.d(TAG, "nREPL-autostart: requiring clojure-android.repl.server");
                    long t0 = System.currentTimeMillis();
                    ((clojure.lang.IFn) require).invoke(
                        clojure.java.api.Clojure.read("clojure-android.repl.server"));
                    long requireMs = System.currentTimeMillis() - t0;
                    Log.d(TAG, "nREPL-autostart: require completed in " + requireMs + "ms");

                    Log.d(TAG, "nREPL-autostart: calling (start) on port 7888");
                    Object startServer = clojure.java.api.Clojure.var(
                        "clojure-android.repl.server", "start");
                    t0 = System.currentTimeMillis();
                    Object server = ((clojure.lang.IFn) startServer).invoke();
                    long startMs = System.currentTimeMillis() - t0;
                    Log.i(TAG, "nREPL server started on port 7888 in " + startMs
                            + "ms (server=" + server + ")");
                } catch (Throwable t) {
                    Log.e(TAG, "nREPL auto-start failed: " + t.getClass().getName()
                            + ": " + t.getMessage(), t);
                }
            },
            "nREPL-autostart",
            1048576 // 1MB stack
        );
        starter.setDaemon(true);
        starter.start();
    }

    /**
     * Loads AOT-compiled Clojure namespaces synchronously on the calling thread.
     * Call this from your Activity or Application after Clojure runtime init.
     *
     * @param namespaces fully-qualified namespace names to load
     */
    public static void loadNamespaces(String... namespaces) {
        try {
            Object require = clojure.java.api.Clojure.var("clojure.core", "require");
            for (String ns : namespaces) {
                ((clojure.lang.IFn) require).invoke(clojure.java.api.Clojure.read(ns));
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load Clojure namespaces", e);
            throw new RuntimeException("Clojure namespace loading failed", e);
        }
    }

    /**
     * Loads Clojure namespaces asynchronously on a background thread with a 1MB
     * stack (sufficient for Clojure's deep initialization stack frames).
     *
     * @param callback called on the background thread after loading completes
     * @param namespaces fully-qualified namespace names to load
     */
    public static void loadNamespacesAsync(Runnable callback, String... namespaces) {
        Thread loader = new Thread(
            Thread.currentThread().getThreadGroup(),
            () -> {
                loadNamespaces(namespaces);
                if (callback != null) {
                    callback.run();
                }
            },
            "ClojureLoader",
            1048576 // 1MB stack
        );
        loader.start();
    }
}
