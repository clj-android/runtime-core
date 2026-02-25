package com.goodanser.clj_android.runtime;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Base Activity that automatically bridges into a Clojure namespace.
 *
 * <p>Subclass this instead of {@link Activity} to have lifecycle methods
 * delegated to functions in a corresponding Clojure namespace. The namespace
 * is derived from the subclass name by convention:</p>
 *
 * <pre>
 *   com.example.foo.NekoActivity  →  com.example.foo.neko-activity
 *   com.example.foo.MainActivity  →  com.example.foo.main-activity
 * </pre>
 *
 * <p>The Clojure namespace may define any of these functions (all optional):</p>
 * <ul>
 *   <li>{@code (on-create [activity bundle])} — called from {@code onCreate}</li>
 *   <li>{@code (on-start [activity])}, {@code (on-resume [activity])},
 *       {@code (on-pause [activity])}, {@code (on-stop [activity])},
 *       {@code (on-destroy [activity])}</li>
 *   <li>{@code (on-save-instance-state [activity bundle])}</li>
 *   <li>{@code (on-restore-instance-state [activity bundle])}</li>
 *   <li>{@code (make-ui [activity])} — returns a {@link View}; used by
 *       {@link #reloadUi()} and as a fallback if {@code on-create} is absent</li>
 * </ul>
 *
 * <p>Override {@link #getClojureNamespace()} to use a custom namespace instead
 * of the convention-based one.</p>
 */
public class ClojureActivity extends Activity {

    private static final String TAG = "ClojureActivity";

    /**
     * Tracks the most recent instance of each ClojureActivity subclass,
     * keyed by Clojure namespace name. Values are weak references so
     * destroyed activities can be garbage-collected even if {@code onDestroy}
     * is not called.
     */
    private static final Map<String, WeakReference<ClojureActivity>> activeInstances =
        new ConcurrentHashMap<>();

    /** The resolved Clojure namespace name for this activity. */
    private String clojureNamespace;

    /** Whether the Clojure namespace was successfully required. */
    private boolean namespaceLoaded = false;

    // ---------------------------------------------------------------
    // Namespace resolution
    // ---------------------------------------------------------------

    /**
     * Returns the Clojure namespace corresponding to this activity.
     * Override to use a custom namespace instead of the convention-based one.
     *
     * <p>Default convention: the fully-qualified class name is converted by
     * keeping the package unchanged (underscores become hyphens) and
     * converting the simple class name from CamelCase to kebab-case.</p>
     *
     * @return the Clojure namespace name
     */
    protected String getClojureNamespace() {
        return classNameToNamespace(getClass().getName());
    }

    /**
     * Converts a fully-qualified Java class name to a Clojure namespace.
     *
     * <p>Examples:</p>
     * <ul>
     *   <li>{@code com.example.foo.NekoActivity → com.example.foo.neko-activity}</li>
     *   <li>{@code com.example.foo_bar.MyHTTPActivity → com.example.foo-bar.my-http-activity}</li>
     * </ul>
     */
    static String classNameToNamespace(String className) {
        int lastDot = className.lastIndexOf('.');
        String pkg = (lastDot >= 0) ? className.substring(0, lastDot) : "";
        String simpleName = (lastDot >= 0) ? className.substring(lastDot + 1) : className;

        // Convert CamelCase to kebab-case
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < simpleName.length(); i++) {
            char c = simpleName.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                char prev = simpleName.charAt(i - 1);
                if (Character.isLowerCase(prev)) {
                    // e.g. nekoA -> neko-a
                    sb.append('-');
                } else if (Character.isUpperCase(prev)
                           && i + 1 < simpleName.length()
                           && Character.isLowerCase(simpleName.charAt(i + 1))) {
                    // e.g. HTTPServer: the 'S' starts a new word after acronym
                    sb.append('-');
                }
            }
            sb.append(Character.toLowerCase(c));
        }

        String kebab = sb.toString();
        if (pkg.isEmpty()) {
            return kebab;
        }
        // Java packages use _ where Clojure namespaces use -
        return pkg.replace('_', '-') + "." + kebab;
    }

    // ---------------------------------------------------------------
    // Var lookup helpers
    // ---------------------------------------------------------------

    /**
     * Looks up a var in this activity's Clojure namespace.
     * Returns {@code null} if the var does not exist or is unbound.
     */
    private clojure.lang.IFn lookupFn(String fnName) {
        try {
            clojure.lang.Var v = (clojure.lang.Var)
                clojure.java.api.Clojure.var(clojureNamespace, fnName);
            return v.isBound() ? v : null;
        } catch (Exception e) {
            Log.w(TAG, "Error looking up " + clojureNamespace + "/" + fnName, e);
            return null;
        }
    }

    /**
     * Requires the Clojure namespace, making its vars available.
     */
    private boolean requireNamespace() {
        try {
            clojure.lang.IFn require = clojure.java.api.Clojure.var("clojure.core", "require");
            require.invoke(clojure.java.api.Clojure.read(clojureNamespace));
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to require namespace: " + clojureNamespace, e);
            return false;
        }
    }

    // ---------------------------------------------------------------
    // Lifecycle methods
    // ---------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        clojureNamespace = getClojureNamespace();
        Log.i(TAG, "onCreate: namespace=" + clojureNamespace
              + " class=" + getClass().getName());

        // Register for REPL access
        activeInstances.put(clojureNamespace, new WeakReference<>(this));

        // Require the namespace
        namespaceLoaded = requireNamespace();
        if (!namespaceLoaded) {
            showError("Failed to load Clojure namespace: " + clojureNamespace);
            return;
        }

        // Delegate to on-create
        clojure.lang.IFn onCreate = lookupFn("on-create");
        if (onCreate != null) {
            try {
                onCreate.invoke(this, savedInstanceState);
            } catch (Exception e) {
                Log.e(TAG, "on-create failed", e);
                showError("on-create failed: " + e.getMessage());
            }
            return;
        }

        // Fallback: if no on-create, try make-ui directly
        clojure.lang.IFn makeUi = lookupFn("make-ui");
        if (makeUi != null) {
            try {
                View view = (View) makeUi.invoke(this);
                if (view != null) {
                    setContentView(view);
                }
            } catch (Exception e) {
                Log.e(TAG, "make-ui failed", e);
                showError("make-ui failed: " + e.getMessage());
            }
        } else {
            Log.w(TAG, "No on-create or make-ui found in " + clojureNamespace);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        invokeLifecycle("on-start");
    }

    @Override
    protected void onResume() {
        super.onResume();
        invokeLifecycle("on-resume");
    }

    @Override
    protected void onPause() {
        super.onPause();
        invokeLifecycle("on-pause");
    }

    @Override
    protected void onStop() {
        super.onStop();
        invokeLifecycle("on-stop");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        invokeLifecycle("on-destroy");
        activeInstances.remove(clojureNamespace);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (!namespaceLoaded) return;
        clojure.lang.IFn fn = lookupFn("on-save-instance-state");
        if (fn != null) {
            try {
                fn.invoke(this, outState);
            } catch (Exception e) {
                Log.e(TAG, "on-save-instance-state failed", e);
            }
        }
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        if (!namespaceLoaded) return;
        clojure.lang.IFn fn = lookupFn("on-restore-instance-state");
        if (fn != null) {
            try {
                fn.invoke(this, savedInstanceState);
            } catch (Exception e) {
                Log.e(TAG, "on-restore-instance-state failed", e);
            }
        }
    }

    /**
     * Invokes a single-argument lifecycle function in the Clojure namespace.
     */
    private void invokeLifecycle(String fnName) {
        if (!namespaceLoaded) return;
        clojure.lang.IFn fn = lookupFn(fnName);
        if (fn != null) {
            try {
                fn.invoke(this);
            } catch (Exception e) {
                Log.e(TAG, fnName + " failed", e);
            }
        }
    }

    // ---------------------------------------------------------------
    // UI reload
    // ---------------------------------------------------------------

    /**
     * Reloads the UI by calling {@code make-ui} in the Clojure namespace
     * and setting the result as the content view. Always runs on the UI
     * thread, so it is safe to call from the REPL or any background thread.
     */
    public void reloadUi() {
        runOnUiThread(() -> {
            if (!namespaceLoaded) {
                Log.w(TAG, "reloadUi: namespace not loaded");
                return;
            }
            clojure.lang.IFn makeUi = lookupFn("make-ui");
            if (makeUi == null) {
                Log.w(TAG, "reloadUi: no make-ui in " + clojureNamespace);
                return;
            }
            try {
                View view = (View) makeUi.invoke(this);
                if (view != null) {
                    setContentView(view);
                    Log.i(TAG, "UI reloaded for " + clojureNamespace);
                }
            } catch (Exception e) {
                Log.e(TAG, "reloadUi failed", e);
            }
        });
    }

    // ---------------------------------------------------------------
    // Static instance access (for REPL)
    // ---------------------------------------------------------------

    /**
     * Returns the most recently created instance whose Clojure namespace
     * matches the given name, or {@code null} if none is active.
     *
     * <p>Intended for REPL use:</p>
     * <pre>
     *   ClojureActivity.getInstance("com.example.foo.neko-activity")
     * </pre>
     */
    public static ClojureActivity getInstance(String namespace) {
        WeakReference<ClojureActivity> ref = activeInstances.get(namespace);
        return (ref != null) ? ref.get() : null;
    }

    /**
     * Reloads the UI of all currently tracked ClojureActivity instances.
     */
    public static void reloadAll() {
        for (Map.Entry<String, WeakReference<ClojureActivity>> entry
                : activeInstances.entrySet()) {
            ClojureActivity activity = entry.getValue().get();
            if (activity != null && !activity.isFinishing() && !activity.isDestroyed()) {
                Log.i(TAG, "reloadAll: reloading " + entry.getKey());
                activity.reloadUi();
            }
        }
    }

    // ---------------------------------------------------------------
    // Accessors
    // ---------------------------------------------------------------

    /**
     * Returns the Clojure namespace name for this activity.
     * Available after {@code onCreate}.
     */
    public String getNamespace() {
        return clojureNamespace;
    }

    // ---------------------------------------------------------------
    // Error fallback
    // ---------------------------------------------------------------

    private void showError(String message) {
        TextView tv = new TextView(this);
        tv.setText(message);
        tv.setPadding(32, 32, 32, 32);
        tv.setTextSize(16);
        setContentView(tv);
    }
}
