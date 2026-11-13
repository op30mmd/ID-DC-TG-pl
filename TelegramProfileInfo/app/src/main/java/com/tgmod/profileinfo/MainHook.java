package com.tgmod.profileinfo;

import android.content.Context;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.util.Log;
import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Obfuscation-immune LSPosed module that displays a user's Data Center (DC)
 * and User ID right under their profile picture in ProfileActivity.
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String LOG_TAG = "TG-ProfileInfo";
    private static final String TAG = "[TG-ProfileInfo] ";

    private static final Set<String> TARGET_PACKAGES = new HashSet<>(Arrays.asList(
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "org.telegram.messenger.beta"
    ));

    private final Map<View, String> viewInfoMap =
            Collections.synchronizedMap(new WeakHashMap<View, String>());
    private final Set<Class<?>> hookedTextClasses =
            Collections.synchronizedSet(new HashSet<Class<?>>());

    private static void logD(String msg) {
        XposedBridge.log(TAG + msg);
        Log.d(LOG_TAG, msg);
    }

    private static void logE(String msg, Throwable t) {
        XposedBridge.log(TAG + "ERROR: " + msg);
        if (t != null) {
            XposedBridge.log(t);
        }
        Log.e(LOG_TAG, msg, t);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PACKAGES.contains(lpparam.packageName)) {
            return;
        }

        logD("handleLoadPackage: pkg=" + lpparam.packageName + ", proc=" + lpparam.processName);

        ClassLoader cl = lpparam.classLoader;
        Class<?> profileActivityCls = XposedHelpers.findClassIfExists("org.telegram.ui.ProfileActivity", cl);
        if (profileActivityCls == null) {
            logE("ProfileActivity class NOT found in " + lpparam.packageName, null);
            return;
        }

        try {
            XposedHelpers.findAndHookMethod(
                    profileActivityCls,
                    "createView",
                    Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            attachProfileInfo(param.thisObject, cl);
                        }
                    }
            );
            logD("Successfully hooked ProfileActivity.createView(Context)");
        } catch (Throwable t) {
            logE("Failed to hook ProfileActivity.createView(Context)", t);
        }
    }

    private void attachProfileInfo(Object profileActivity, ClassLoader cl) {
        try {
            Bundle args = (Bundle) XposedHelpers.callMethod(profileActivity, "getArguments");
            if (args == null) {
                return;
            }
            long userId = args.getLong("user_id", 0L);
            if (userId == 0L) {
                return;
            }

            Object messagesController = XposedHelpers.callMethod(profileActivity, "getMessagesController");
            Object user = findUserById(messagesController, userId, cl);
            int dcId = extractDcId(user);
            String firstName = extractStringField(user, "first_name");

            String badge = dcId > 0
                    ? " · DC " + dcId + " · ID " + userId
                    : " · ID " + userId;

            List<View> candidates = new ArrayList<>();
            for (Field field : profileActivity.getClass().getDeclaredFields()) {
                Class<?> fType = field.getType();
                if (!View.class.isAssignableFrom(fType)) {
                    continue;
                }
                if (!hasMethod(fType, "setRightDrawableInside", boolean.class)) {
                    continue;
                }
                field.setAccessible(true);
                Object viewObj = field.get(profileActivity);
                if (viewObj instanceof View) {
                    candidates.add((View) viewObj);
                }
            }

            logD("attachProfileInfo: userId=" + userId + ", dcId=" + dcId + ", candidates=" + candidates.size());

            for (View view : candidates) {
                CharSequence existing = getCurrentText(view);
                // Skip the display name view (first_name) when multiple SimpleTextView fields exist
                if (candidates.size() > 1 && firstName != null && !firstName.isEmpty()
                        && existing != null && existing.toString().startsWith(firstName)) {
                    logD("Skipping name view " + view.getClass().getSimpleName() + " ('" + existing + "')");
                    continue;
                }

                hookSimpleTextViewHierarchy(view.getClass());
                viewInfoMap.put(view, badge);

                if (existing != null && !existing.toString().contains("ID " + userId)) {
                    invokeTextSetter(view, appendBadge(existing, badge));
                }
            }
        } catch (Throwable t) {
            logE("attachProfileInfo error", t);
        }
    }

    /**
     * Walks the class hierarchy (from the view's concrete class up to View.class)
     * so inherited text setters on subclasses like org.telegram.ui.gw0 are hooked.
     */
    private void hookSimpleTextViewHierarchy(Class<?> leafCls) {
        for (Class<?> cls = leafCls; cls != null && cls != View.class && cls != Object.class; cls = cls.getSuperclass()) {
            if (!hookedTextClasses.add(cls)) {
                continue;
            }
            int hookedCount = 0;
            for (Method m : cls.getDeclaredMethods()) {
                if (isTextSetterMethod(m)) {
                    logD("Hooking text setter: " + cls.getName() + "." + m.getName()
                            + "(" + Arrays.toString(m.getParameterTypes()) + ")");
                    hookedCount++;
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!(param.thisObject instanceof View)) {
                                return;
                            }
                            View view = (View) param.thisObject;
                            String badge = viewInfoMap.get(view);
                            if (badge == null) {
                                return;
                            }
                            CharSequence text = (CharSequence) param.args[0];
                            if (text != null && !text.toString().contains(badge)) {
                                param.args[0] = appendBadge(text, badge);
                                logD("Appended badge -> '" + param.args[0] + "'");
                            }
                        }
                    });
                }
            }
            logD("Hooked " + hookedCount + " text setter(s) on " + cls.getName());
        }
    }

    private static boolean isTextSetterMethod(Method m) {
        Class<?>[] params = m.getParameterTypes();
        Class<?> ret = m.getReturnType();
        if (ret != boolean.class && ret != void.class) {
            return false;
        }
        if (params.length == 2 && params[0] == CharSequence.class && params[1] == boolean.class) {
            return true;
        }
        return params.length == 1 && params[0] == CharSequence.class;
    }

    private static CharSequence appendBadge(CharSequence base, String badge) {
        if (base instanceof SpannableStringBuilder) {
            SpannableStringBuilder ssb = new SpannableStringBuilder(base);
            ssb.append(badge);
            return ssb;
        }
        return base + badge;
    }

    private static CharSequence getCurrentText(View view) {
        try {
            Method m = view.getClass().getMethod("getText");
            Object res = m.invoke(view);
            return res instanceof CharSequence ? (CharSequence) res : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void invokeTextSetter(View view, CharSequence text) {
        for (Class<?> cls = view.getClass(); cls != null && cls != View.class && cls != Object.class; cls = cls.getSuperclass()) {
            for (Method m : cls.getDeclaredMethods()) {
                Class<?>[] params = m.getParameterTypes();
                Class<?> ret = m.getReturnType();
                if (params.length == 2 && params[0] == CharSequence.class && params[1] == boolean.class
                        && (ret == boolean.class || ret == void.class)) {
                    try {
                        m.setAccessible(true);
                        m.invoke(view, text, false);
                        logD("invokeTextSetter(2-arg) succeeded via " + cls.getSimpleName() + "." + m.getName());
                        return;
                    } catch (Throwable t) {
                        logE("invokeTextSetter failed on " + m.getName(), t);
                    }
                }
            }
        }
        for (Class<?> cls = view.getClass(); cls != null && cls != View.class && cls != Object.class; cls = cls.getSuperclass()) {
            for (Method m : cls.getDeclaredMethods()) {
                Class<?>[] params = m.getParameterTypes();
                Class<?> ret = m.getReturnType();
                if (params.length == 1 && params[0] == CharSequence.class
                        && (ret == boolean.class || ret == void.class)) {
                    try {
                        m.setAccessible(true);
                        m.invoke(view, text);
                        logD("invokeTextSetter(1-arg) succeeded via " + cls.getSimpleName() + "." + m.getName());
                        return;
                    } catch (Throwable t) {
                        logE("invokeTextSetter failed on " + m.getName(), t);
                    }
                }
            }
        }
    }

    private static Object findUserById(Object messagesController, long userId, ClassLoader cl) {
        if (messagesController == null) {
            return null;
        }
        Class<?> userCls = XposedHelpers.findClassIfExists("org.telegram.tgnet.TLRPC$User", cl);
        if (userCls == null) {
            return null;
        }
        for (Method m : messagesController.getClass().getMethods()) {
            Class<?>[] params = m.getParameterTypes();
            if (params.length == 1
                    && (params[0] == Long.class || params[0] == long.class)
                    && userCls.isAssignableFrom(m.getReturnType())) {
                try {
                    Object res = m.invoke(messagesController, userId);
                    if (res != null) {
                        return res;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static String extractStringField(Object obj, String fieldName) {
        if (obj == null) {
            return null;
        }
        try {
            Object val = XposedHelpers.getObjectField(obj, fieldName);
            return val instanceof String ? (String) val : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int extractDcId(Object user) {
        if (user == null) {
            return 0;
        }
        try {
            Object photo = XposedHelpers.getObjectField(user, "photo");
            if (photo == null) {
                return 0;
            }
            int dcId = XposedHelpers.getIntField(photo, "dc_id");
            if (dcId > 0) {
                return dcId;
            }
            Object photoBig = XposedHelpers.getObjectField(photo, "photo_big");
            if (photoBig != null) {
                return XposedHelpers.getIntField(photoBig, "dc_id");
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static boolean hasMethod(Class<?> cls, String name, Class<?>... paramTypes) {
        try {
            cls.getMethod(name, paramTypes);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }
}
