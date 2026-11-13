package com.tgmod.profileinfo;

import android.content.Context;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.util.Log;
import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
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
 *
 * Includes verbose debug logging to both LSPosed Logs (XposedBridge.log)
 * and Android logcat (tag: TG-ProfileInfo).
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String LOG_TAG = "TG-ProfileInfo";
    private static final String TAG = "[TG-ProfileInfo] ";

    private static final Set<String> TARGET_PACKAGES = new HashSet<>(Arrays.asList(
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "org.telegram.messenger.beta"
    ));

    private final WeakHashMap<View, String> viewInfoMap =
            (WeakHashMap<View, String>) Collections.synchronizedMap(new WeakHashMap<View, String>());
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
        logD("Found ProfileActivity: " + profileActivityCls.getName());

        try {
            XposedHelpers.findAndHookMethod(
                    profileActivityCls,
                    "createView",
                    Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            logD("ProfileActivity.createView() fired on " + param.thisObject);
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
                logD("attachProfileInfo: getArguments() returned null");
                return;
            }
            long userId = args.getLong("user_id", 0L);
            logD("attachProfileInfo: user_id=" + userId + ", chat_id=" + args.getLong("chat_id", 0L));
            if (userId == 0L) {
                logD("attachProfileInfo: skipping (not a user profile)");
                return;
            }

            Object messagesController = XposedHelpers.callMethod(profileActivity, "getMessagesController");
            logD("attachProfileInfo: messagesController=" + (messagesController != null ? messagesController.getClass().getName() : "null"));

            Object user = findUserById(messagesController, userId, cl);
            logD("attachProfileInfo: resolved user=" + (user != null ? user.getClass().getName() : "null"));

            int dcId = extractDcId(user);
            logD("attachProfileInfo: extracted dc_id=" + dcId);

            String badge = dcId > 0
                    ? " · DC " + dcId + " · ID " + userId
                    : " · ID " + userId;

            int matchedViews = 0;
            for (Field field : profileActivity.getClass().getDeclaredFields()) {
                Class<?> fType = field.getType();
                if (!View.class.isAssignableFrom(fType)) {
                    continue;
                }
                boolean hasRightDrawableInside = hasMethod(fType, "setRightDrawableInside", boolean.class);
                if (!hasRightDrawableInside) {
                    continue;
                }
                field.setAccessible(true);
                Object viewObj = field.get(profileActivity);
                logD("Candidate SimpleTextView field: " + field.getName()
                        + " type=" + fType.getName()
                        + " instance=" + viewObj
                        + " tag=" + (viewObj instanceof View ? ((View) viewObj).getTag() : "n/a"));

                if (!(viewObj instanceof View)) {
                    continue;
                }
                View statusView = (View) viewObj;
                matchedViews++;
                hookSimpleTextViewClass(statusView.getClass());
                viewInfoMap.put(statusView, badge);

                CharSequence existing = getCurrentText(statusView);
                logD("Candidate field " + field.getName() + " existingText=" + existing);
                if (existing != null && !existing.toString().contains("ID " + userId)) {
                    invokeTextSetter(statusView, appendBadge(existing, badge));
                }
            }
            logD("attachProfileInfo: total matched SimpleTextView fields=" + matchedViews);
        } catch (Throwable t) {
            logE("attachProfileInfo error", t);
        }
    }

    private void hookSimpleTextViewClass(Class<?> cls) {
        if (!hookedTextClasses.add(cls)) {
            return;
        }
        logD("Inspecting SimpleTextView class for text setters: " + cls.getName());
        int hookedCount = 0;
        for (Method m : cls.getDeclaredMethods()) {
            Class<?>[] params = m.getParameterTypes();
            boolean isTwoArgSetter = params.length == 2
                    && params[0] == CharSequence.class
                    && params[1] == boolean.class
                    && (m.getReturnType() == boolean.class || m.getReturnType() == void.class);
            boolean isOneArgSetter = params.length == 1
                    && params[0] == CharSequence.class
                    && (m.getReturnType() == boolean.class || m.getReturnType() == void.class);

            if (isTwoArgSetter || isOneArgSetter) {
                logD("Hooking text setter: " + cls.getSimpleName() + "." + m.getName()
                        + "(" + Arrays.toString(params) + ") -> " + m.getReturnType().getSimpleName());
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
                        logD("Text setter fired on " + view.getClass().getSimpleName()
                                + " tag=" + view.getTag()
                                + " text='" + text + "'");
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
        for (Method m : view.getClass().getDeclaredMethods()) {
            Class<?>[] params = m.getParameterTypes();
            if (params.length == 2
                    && params[0] == CharSequence.class
                    && params[1] == boolean.class
                    && (m.getReturnType() == boolean.class || m.getReturnType() == void.class)) {
                try {
                    m.setAccessible(true);
                    m.invoke(view, text, false);
                    logD("invokeTextSetter succeeded via " + m.getName());
                    return;
                } catch (Throwable t) {
                    logE("invokeTextSetter failed on " + m.getName(), t);
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
            logE("TLRPC$User class not found", null);
            return null;
        }
        for (Method m : messagesController.getClass().getMethods()) {
            Class<?>[] params = m.getParameterTypes();
            if (params.length == 1
                    && (params[0] == Long.class || params[0] == long.class)
                    && userCls.isAssignableFrom(m.getReturnType())) {
                try {
                    Object res = m.invoke(messagesController, userId);
                    logD("findUserById tried " + m.getName() + "(" + params[0].getSimpleName() + ") -> " + res);
                    if (res != null) {
                        return res;
                    }
                } catch (Throwable t) {
                    logE("findUserById error invoking " + m.getName(), t);
                }
            }
        }
        return null;
    }

    private static int extractDcId(Object user) {
        if (user == null) {
            return 0;
        }
        try {
            Object photo = XposedHelpers.getObjectField(user, "photo");
            if (photo == null) {
                logD("extractDcId: user.photo is null");
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
        } catch (Throwable t) {
            logE("extractDcId error", t);
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
