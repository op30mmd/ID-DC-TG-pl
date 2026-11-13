package com.tgmod.profileinfo;

import android.content.Context;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
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
 * Uses un-obfuscated anchors:
 *   - org.telegram.ui.ProfileActivity (kept by R8)
 *   - BaseFragment.getArguments() -> Bundle key "user_id"
 *   - BaseFragment.getMessagesController() -> method (Long) -> TLRPC$User
 *   - TLRPC$User.id, TLRPC$User.photo.dc_id (TL schema fields, never obfuscated)
 *   - Dynamic discovery of the SimpleTextView status field (onlineTextView)
 *     via its un-obfuscated setRightDrawableInside(boolean) method.
 */
public class MainHook implements IXposedHookLoadPackage {

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

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PACKAGES.contains(lpparam.packageName)) {
            return;
        }

        ClassLoader cl = lpparam.classLoader;
        Class<?> profileActivityCls = XposedHelpers.findClassIfExists("org.telegram.ui.ProfileActivity", cl);
        if (profileActivityCls == null) {
            return;
        }

        XposedBridge.log(TAG + "Hooking ProfileActivity in " + lpparam.packageName);

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
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Failed to hook ProfileActivity.createView: " + t.getMessage());
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

            String badge = dcId > 0
                    ? " · DC " + dcId + " · ID " + userId
                    : " · ID " + userId;

            // Locate SimpleTextView fields on ProfileActivity (e.g., onlineTextView)
            // by checking for the un-obfuscated setRightDrawableInside(boolean) method.
            for (Field field : profileActivity.getClass().getDeclaredFields()) {
                if (!View.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                if (!hasMethod(field.getType(), "setRightDrawableInside", boolean.class)) {
                    continue;
                }
                field.setAccessible(true);
                Object viewObj = field.get(profileActivity);
                if (!(viewObj instanceof View)) {
                    continue;
                }
                View statusView = (View) viewObj;
                // onlineTextView carries an Integer theme tag set in updateProfileData()
                // or is the status SimpleTextView; hook its (CharSequence, boolean) setter.
                hookSimpleTextViewClass(statusView.getClass());
                viewInfoMap.put(statusView, badge);

                // Refresh immediately if text was already populated during createView()
                CharSequence existing = getCurrentText(statusView);
                if (existing != null && !existing.toString().contains("ID " + userId)) {
                    invokeTextSetter(statusView, appendBadge(existing, badge));
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "attachProfileInfo error: " + t.getMessage());
        }
    }

    private void hookSimpleTextViewClass(Class<?> cls) {
        if (!hookedTextClasses.add(cls)) {
            return;
        }
        for (Method m : cls.getDeclaredMethods()) {
            Class<?>[] params = m.getParameterTypes();
            if (params.length == 2
                    && params[0] == CharSequence.class
                    && params[1] == boolean.class
                    && m.getReturnType() == boolean.class) {
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
                        // Only append to the status view (tagged with Integer theme key)
                        // rather than the display name view.
                        if (!(view.getTag() instanceof Integer)) {
                            return;
                        }
                        CharSequence text = (CharSequence) param.args[0];
                        if (text != null && !text.toString().contains(badge)) {
                            param.args[0] = appendBadge(text, badge);
                        }
                    }
                });
            }
        }
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
                    && m.getReturnType() == boolean.class) {
                try {
                    m.setAccessible(true);
                    m.invoke(view, text, false);
                    return;
                } catch (Throwable ignored) {
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
