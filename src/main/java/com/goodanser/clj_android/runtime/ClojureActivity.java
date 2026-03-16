package com.goodanser.clj_android.runtime;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.util.Log;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

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
 *
 * <h4>Lifecycle</h4>
 * <ul>
 *   <li>{@code (on-create [activity bundle])}</li>
 *   <li>{@code (on-start [activity])}, {@code (on-restart [activity])},
 *       {@code (on-resume [activity])}, {@code (on-pause [activity])},
 *       {@code (on-stop [activity])}, {@code (on-destroy [activity])}</li>
 *   <li>{@code (on-save-instance-state [activity bundle])}</li>
 *   <li>{@code (on-restore-instance-state [activity bundle])}</li>
 *   <li>{@code (make-ui [activity])} — returns a {@link View}; used by
 *       {@link #reloadUi()} and as a fallback if {@code on-create} is absent</li>
 * </ul>
 *
 * <h4>Intent &amp; navigation</h4>
 * <ul>
 *   <li>{@code (on-activity-result [activity request-code result-code intent])}
 *       — only called for request codes below {@link #AUTO_REQUEST_CODE_START}
 *       (i.e. manually chosen codes 0–9999). Callbacks registered via
 *       {@link #registerResultCallback} are dispatched first and consume
 *       their request code.</li>
 *   <li>{@code (on-new-intent [activity intent])}</li>
 *   <li>{@code (on-back-pressed [activity])} — if defined, replaces default
 *       back behavior; must handle navigation itself</li>
 *   <li>{@code (on-request-permissions-result [activity request-code permissions grant-results])}
 *       — only called for manual request codes (0–9999), same as
 *       {@code on-activity-result}.</li>
 * </ul>
 *
 * <h4>Menus</h4>
 * <ul>
 *   <li>{@code (on-create-options-menu [activity menu])} — return truthy to show menu</li>
 *   <li>{@code (on-prepare-options-menu [activity menu])} — return truthy to show menu</li>
 *   <li>{@code (on-options-item-selected [activity item])} — return truthy if consumed</li>
 *   <li>{@code (on-create-context-menu [activity menu view menu-info])}</li>
 *   <li>{@code (on-context-item-selected [activity item])} — return truthy if consumed</li>
 * </ul>
 *
 * <h4>Configuration &amp; memory</h4>
 * <ul>
 *   <li>{@code (on-configuration-changed [activity config])}</li>
 *   <li>{@code (on-low-memory [activity])}</li>
 *   <li>{@code (on-trim-memory [activity level])}</li>
 * </ul>
 *
 * <h4>Window</h4>
 * <ul>
 *   <li>{@code (on-window-focus-changed [activity has-focus])}</li>
 *   <li>{@code (on-attached-to-window [activity])}</li>
 *   <li>{@code (on-detached-from-window [activity])}</li>
 * </ul>
 *
 * <h4>Multi-window</h4>
 * <ul>
 *   <li>{@code (on-multi-window-mode-changed [activity in-multi-window config])}</li>
 *   <li>{@code (on-picture-in-picture-mode-changed [activity in-pip config])}</li>
 *   <li>{@code (on-user-leave-hint [activity])}</li>
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

    /**
     * Auto-incrementing request code counter for registered callbacks.
     * Starts at 10000, reserving 0–9999 for manual use with
     * {@code on-activity-result}.
     */
    private final AtomicInteger nextRequestCode = new AtomicInteger(10000);

    /**
     * One-shot callbacks registered via {@link #registerResultCallback}.
     * Key: request code. Value: two-element array [onResult, onCancel],
     * each a {@code clojure.lang.IFn} or {@code null}.
     */
    private final ConcurrentHashMap<Integer, clojure.lang.IFn[]> resultCallbacks =
        new ConcurrentHashMap<>();

    /**
     * One-shot callbacks registered via {@link #registerPermissionCallback}.
     * Key: request code. Value: {@code clojure.lang.IFn} called with
     * {@code (callback activity permissions grantResults)}.
     */
    private final ConcurrentHashMap<Integer, clojure.lang.IFn> permissionCallbacks =
        new ConcurrentHashMap<>();

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
    protected void onRestart() {
        super.onRestart();
        invokeLifecycle("on-restart");
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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        // Check registered one-shot callbacks first (auto-generated codes)
        clojure.lang.IFn[] cbs = resultCallbacks.remove(requestCode);
        if (cbs != null) {
            try {
                if (resultCode == RESULT_OK && cbs[0] != null) {
                    cbs[0].invoke(this, resultCode, data);
                } else if (resultCode != RESULT_OK && cbs[1] != null) {
                    cbs[1].invoke(this);
                }
            } catch (Exception e) {
                Log.e(TAG, "Registered result callback failed for code " + requestCode, e);
            }
            return;
        }

        // Fall through to namespace delegate for manual request codes
        if (!namespaceLoaded) return;
        clojure.lang.IFn fn = lookupFn("on-activity-result");
        if (fn != null) {
            try {
                fn.invoke(this, requestCode, resultCode, data);
            } catch (Exception e) {
                Log.e(TAG, "on-activity-result failed", e);
            }
        }
    }

    // ---------------------------------------------------------------
    // Intent & navigation
    // ---------------------------------------------------------------

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (!namespaceLoaded) return;
        clojure.lang.IFn fn = lookupFn("on-new-intent");
        if (fn != null) {
            try {
                fn.invoke(this, intent);
            } catch (Exception e) {
                Log.e(TAG, "on-new-intent failed", e);
            }
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (!namespaceLoaded) {
            super.onBackPressed();
            return;
        }
        clojure.lang.IFn fn = lookupFn("on-back-pressed");
        if (fn != null) {
            try {
                fn.invoke(this);
            } catch (Exception e) {
                Log.e(TAG, "on-back-pressed failed", e);
                super.onBackPressed();
            }
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
            String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        // Check registered one-shot callbacks first (auto-generated codes)
        clojure.lang.IFn cb = permissionCallbacks.remove(requestCode);
        if (cb != null) {
            try {
                cb.invoke(this, permissions, grantResults);
            } catch (Exception e) {
                Log.e(TAG, "Registered permission callback failed for code " + requestCode, e);
            }
            return;
        }

        // Fall through to namespace delegate for manual request codes
        if (!namespaceLoaded) return;
        clojure.lang.IFn fn = lookupFn("on-request-permissions-result");
        if (fn != null) {
            try {
                fn.invoke(this, requestCode, permissions, grantResults);
            } catch (Exception e) {
                Log.e(TAG, "on-request-permissions-result failed", e);
            }
        }
    }

    // ---------------------------------------------------------------
    // Menus
    // ---------------------------------------------------------------

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (namespaceLoaded) {
            clojure.lang.IFn fn = lookupFn("on-create-options-menu");
            if (fn != null) {
                try {
                    Object result = fn.invoke(this, menu);
                    return isTruthy(result);
                } catch (Exception e) {
                    Log.e(TAG, "on-create-options-menu failed", e);
                }
            }
        }
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        if (namespaceLoaded) {
            clojure.lang.IFn fn = lookupFn("on-prepare-options-menu");
            if (fn != null) {
                try {
                    Object result = fn.invoke(this, menu);
                    return isTruthy(result);
                } catch (Exception e) {
                    Log.e(TAG, "on-prepare-options-menu failed", e);
                }
            }
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (namespaceLoaded) {
            clojure.lang.IFn fn = lookupFn("on-options-item-selected");
            if (fn != null) {
                try {
                    Object result = fn.invoke(this, item);
                    if (isTruthy(result)) return true;
                } catch (Exception e) {
                    Log.e(TAG, "on-options-item-selected failed", e);
                }
            }
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v,
            ContextMenu.ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        if (!namespaceLoaded) return;
        clojure.lang.IFn fn = lookupFn("on-create-context-menu");
        if (fn != null) {
            try {
                fn.invoke(this, menu, v, menuInfo);
            } catch (Exception e) {
                Log.e(TAG, "on-create-context-menu failed", e);
            }
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        if (namespaceLoaded) {
            clojure.lang.IFn fn = lookupFn("on-context-item-selected");
            if (fn != null) {
                try {
                    Object result = fn.invoke(this, item);
                    if (isTruthy(result)) return true;
                } catch (Exception e) {
                    Log.e(TAG, "on-context-item-selected failed", e);
                }
            }
        }
        return super.onContextItemSelected(item);
    }

    // ---------------------------------------------------------------
    // Configuration & memory
    // ---------------------------------------------------------------

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (!namespaceLoaded) return;
        clojure.lang.IFn fn = lookupFn("on-configuration-changed");
        if (fn != null) {
            try {
                fn.invoke(this, newConfig);
            } catch (Exception e) {
                Log.e(TAG, "on-configuration-changed failed", e);
            }
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        invokeLifecycle("on-low-memory");
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (!namespaceLoaded) return;
        clojure.lang.IFn fn = lookupFn("on-trim-memory");
        if (fn != null) {
            try {
                fn.invoke(this, level);
            } catch (Exception e) {
                Log.e(TAG, "on-trim-memory failed", e);
            }
        }
    }

    // ---------------------------------------------------------------
    // Window
    // ---------------------------------------------------------------

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!namespaceLoaded) return;
        clojure.lang.IFn fn = lookupFn("on-window-focus-changed");
        if (fn != null) {
            try {
                fn.invoke(this, hasFocus);
            } catch (Exception e) {
                Log.e(TAG, "on-window-focus-changed failed", e);
            }
        }
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        invokeLifecycle("on-attached-to-window");
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        invokeLifecycle("on-detached-from-window");
    }

    // ---------------------------------------------------------------
    // Multi-window
    // ---------------------------------------------------------------

    @Override
    public void onMultiWindowModeChanged(boolean isInMultiWindowMode,
            Configuration newConfig) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig);
        if (!namespaceLoaded) return;
        clojure.lang.IFn fn = lookupFn("on-multi-window-mode-changed");
        if (fn != null) {
            try {
                fn.invoke(this, isInMultiWindowMode, newConfig);
            } catch (Exception e) {
                Log.e(TAG, "on-multi-window-mode-changed failed", e);
            }
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode,
            Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        if (!namespaceLoaded) return;
        clojure.lang.IFn fn = lookupFn("on-picture-in-picture-mode-changed");
        if (fn != null) {
            try {
                fn.invoke(this, isInPictureInPictureMode, newConfig);
            } catch (Exception e) {
                Log.e(TAG, "on-picture-in-picture-mode-changed failed", e);
            }
        }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        invokeLifecycle("on-user-leave-hint");
    }

    // ---------------------------------------------------------------
    // Activity result callbacks
    // ---------------------------------------------------------------

    /**
     * The lowest request code reserved for automatic allocation.
     * Request codes 0–9999 are safe for manual use in Clojure
     * namespaces via {@code on-activity-result}.
     */
    public static final int AUTO_REQUEST_CODE_START = 10000;

    /**
     * Registers one-shot callbacks for an activity result and returns
     * the allocated request code. The callbacks are removed after
     * {@code onActivityResult} fires for this code.
     *
     * @param onResult called with {@code (onResult activity resultCode data)}
     *                 when a result arrives; may be {@code null}
     * @param onCancel called with {@code (onCancel activity)} when the
     *                 result code is not {@code RESULT_OK}; may be {@code null}
     * @return the request code to pass to
     *         {@link #startActivityForResult(Intent, int)}
     */
    public int registerResultCallback(clojure.lang.IFn onResult,
                                      clojure.lang.IFn onCancel) {
        int code = nextRequestCode.getAndIncrement();
        resultCallbacks.put(code, new clojure.lang.IFn[]{ onResult, onCancel });
        return code;
    }

    /**
     * Registers a one-shot callback for a permission request result and
     * returns the allocated request code.
     *
     * @param callback called with
     *        {@code (callback activity permissions grantResults)}
     * @return the request code to pass to
     *         {@link #requestPermissions(String[], int)}
     */
    public int registerPermissionCallback(clojure.lang.IFn callback) {
        int code = nextRequestCode.getAndIncrement();
        permissionCallbacks.put(code, callback);
        return code;
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

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

    /**
     * Interprets a Clojure return value as a boolean using Clojure truthiness:
     * {@code nil} and {@code false} are falsy, everything else is truthy.
     */
    private static boolean isTruthy(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean) return (Boolean) value;
        return true;
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
