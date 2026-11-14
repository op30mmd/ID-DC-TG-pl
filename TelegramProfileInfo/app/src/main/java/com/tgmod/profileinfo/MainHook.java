package com.tgmod.profileinfo;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.query.matchers.MethodsMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.MethodData;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Obfuscation-immune LSPosed module that displays "ID: <id>, DC: <dc>"
 * right under profile pictures in ProfileActivity for users and supergroups,
 * and opens Telegram's native ItemOptions popup menu (resolved via DexKit).
 */
public class MainHook implements IXposedHookZygoteInit, IXposedHookLoadPackage {

    private static final String LOG_TAG = "TG-ProfileInfo";
    private static final String TAG = "[TG-ProfileInfo] ";

    private static final Set<String> TARGET_PACKAGES = new HashSet<>(Arrays.asList(
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "org.telegram.messenger.beta"
    ));

    private static volatile String modulePath = null;
    private static volatile boolean dexKitScanned = false;
    private static volatile Method makeOptionsMethod = null;
    private static volatile Method addItemMethod = null;
    private static volatile Method showOptionsMethod = null;

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
    public void initZygote(StartupParam startupParam) {
        modulePath = startupParam.modulePath;
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PACKAGES.contains(lpparam.packageName)) {
            return;
        }

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
                            Context ctx = (Context) param.args[0];
                            discoverItemOptionsWithDexKitAsync(ctx, lpparam.appInfo.sourceDir, cl, profileActivityCls.getSuperclass());
                            attachProfileInfo(param.thisObject, ctx, cl);
                        }
                    }
            );
            logD("Hooked ProfileActivity.createView(Context)");
        } catch (Throwable t) {
            logE("Failed to hook ProfileActivity.createView(Context)", t);
        }
    }

    private static boolean loadDexKitNative(Context context) {
        try {
            System.loadLibrary("dexkit");
            return true;
        } catch (Throwable ignored) {
        }
        if (modulePath == null) {
            return false;
        }
        try {
            String abi = Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0
                    ? Build.SUPPORTED_ABIS[0] : "arm64-v8a";
            File outFile = new File(context.getCacheDir(), "libdexkit.so");
            if (!outFile.exists() || outFile.length() == 0) {
                try (ZipFile zf = new ZipFile(modulePath)) {
                    ZipEntry entry = zf.getEntry("lib/" + abi + "/libdexkit.so");
                    if (entry == null) {
                        entry = zf.getEntry("lib/arm64-v8a/libdexkit.so");
                    }
                    if (entry == null) {
                        return false;
                    }
                    try (InputStream in = zf.getInputStream(entry);
                         FileOutputStream out = new FileOutputStream(outFile)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                        }
                    }
                }
            }
            System.load(outFile.getAbsolutePath());
            return true;
        } catch (Throwable t) {
            logE("Failed to load libdexkit.so", t);
            return false;
        }
    }

    private static void discoverItemOptionsWithDexKitAsync(
            Context context,
            String apkPath,
            ClassLoader cl,
            Class<?> baseFragmentCls
    ) {
        if (dexKitScanned || baseFragmentCls == null || apkPath == null) {
            return;
        }
        dexKitScanned = true;
        new Thread(() -> {
            long t0 = System.currentTimeMillis();
            if (!loadDexKitNative(context)) {
                logE("Could not load libdexkit.so", null);
                return;
            }
            try (DexKitBridge bridge = DexKitBridge.create(apkPath)) {
                if (bridge == null) {
                    logE("DexKitBridge.create returned null", null);
                    return;
                }

                // ItemOptions in Telegram 13.x:
                // - static makeOptions(BaseFragment, View) -> Self
                // - add(int, CharSequence, Runnable, boolean) -> Self  (or 3-arg without boolean)
                // - show(): void calling showAtLocation / showAsDropDown
                ClassData itemOptionsData = bridge.findClass(
                        FindClass.create().matcher(
                                ClassMatcher.create().methods(
                                        MethodsMatcher.create().add(
                                                MethodMatcher.create()
                                                        .modifiers(Modifier.STATIC)
                                                        .paramTypes(baseFragmentCls, View.class)
                                        ).add(
                                                MethodMatcher.create()
                                                        .paramTypes(int.class, CharSequence.class, Runnable.class, boolean.class)
                                        )
                                )
                        )
                ).singleOrNull();

                boolean fourArgAdd = true;
                if (itemOptionsData == null) {
                    fourArgAdd = false;
                    itemOptionsData = bridge.findClass(
                            FindClass.create().matcher(
                                    ClassMatcher.create().methods(
                                            MethodsMatcher.create().add(
                                                    MethodMatcher.create()
                                                            .modifiers(Modifier.STATIC)
                                                            .paramTypes(baseFragmentCls, View.class)
                                            ).add(
                                                    MethodMatcher.create()
                                                            .paramTypes(int.class, CharSequence.class, Runnable.class)
                                            )
                                    )
                            )
                    ).singleOrNull();
                }

                if (itemOptionsData == null) {
                    logD("DexKit: ItemOptions class not found in " + (System.currentTimeMillis() - t0) + "ms");
                    return;
                }

                Class<?> itemOptionsCls = cl.loadClass(itemOptionsData.getName());

                // Find show(): 0-arg void method inside ItemOptions that invokes showAtLocation or showAsDropDown
                MethodData showData = bridge.findMethod(
                        FindMethod.create()
                                .searchInClass(java.util.Collections.singletonList(itemOptionsData))
                                .matcher(
                                        MethodMatcher.create()
                                                .paramCount(0)
                                                .returnType(void.class)
                                )
                ).stream().filter(md -> {
                    for (MethodData inv : md.getInvokes()) {
                        String n = inv.getName();
                        if ("showAtLocation".equals(n) || "showAsDropDown".equals(n)) {
                            return true;
                        }
                    }
                    return false;
                }).findFirst().orElse(null);

                Method foundMake = null;
                Method foundAdd = null;
                Method foundShow = showData != null ? showData.getMethodInstance(cl) : null;

                for (Method m : itemOptionsCls.getDeclaredMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (Modifier.isStatic(m.getModifiers())
                            && p.length == 2 && p[0] == baseFragmentCls && p[1] == View.class
                            && m.getReturnType() == itemOptionsCls) {
                        foundMake = m;
                    } else if (!Modifier.isStatic(m.getModifiers())
                            && ((fourArgAdd && p.length == 4 && p[0] == int.class && p[1] == CharSequence.class && p[2] == Runnable.class && p[3] == boolean.class)
                            || (!fourArgAdd && p.length == 3 && p[0] == int.class && p[1] == CharSequence.class && p[2] == Runnable.class))) {
                        if (foundAdd == null || m.getReturnType() == itemOptionsCls) {
                            foundAdd = m;
                        }
                    }
                }

                if (foundMake != null && foundAdd != null && foundShow != null) {
                    foundMake.setAccessible(true);
                    foundAdd.setAccessible(true);
                    foundShow.setAccessible(true);
                    makeOptionsMethod = foundMake;
                    addItemMethod = foundAdd;
                    showOptionsMethod = foundShow;
                    logD("DexKit resolved native ItemOptions: " + itemOptionsCls.getName()
                            + " (make=" + foundMake.getName()
                            + ", add=" + foundAdd.getName()
                            + ", show=" + foundShow.getName()
                            + ") in " + (System.currentTimeMillis() - t0) + "ms");
                } else {
                    logD("DexKit partial match: cls=" + itemOptionsCls.getName()
                            + " make=" + foundMake + " add=" + foundAdd + " show=" + foundShow);
                }
            } catch (Throwable t) {
                logE("DexKit discovery failed", t);
            }
        }, "TG-ProfileInfo-DexKit").start();
    }

    private void attachProfileInfo(Object profileActivity, Context context, ClassLoader cl) {
        try {
            Bundle args = (Bundle) XposedHelpers.callMethod(profileActivity, "getArguments");
            if (args == null) {
                return;
            }

            long userId = args.getLong("user_id", 0L);
            long chatId = args.getLong("chat_id", 0L);
            if (userId == 0L && chatId == 0L) {
                return;
            }

            Object messagesController = XposedHelpers.callMethod(profileActivity, "getMessagesController");
            int dcId;
            String displayId;
            String deepLink;
            String titleOrFirstName;

            if (userId != 0L) {
                Object user = findEntityById(messagesController, userId, "org.telegram.tgnet.TLRPC$User", cl);
                dcId = extractDcId(user);
                displayId = String.valueOf(userId);
                deepLink = "tg://openmessage?user_id=" + userId;
                titleOrFirstName = extractStringField(user, "first_name");
            } else {
                Object chat = findEntityById(messagesController, chatId, "org.telegram.tgnet.TLRPC$Chat", cl);
                dcId = extractDcId(chat);
                boolean isChannelOrSupergroup = chat != null
                        && chat.getClass().getSimpleName().toLowerCase().contains("channel");
                displayId = isChannelOrSupergroup ? ("-100" + chatId) : String.valueOf(chatId);
                deepLink = "tg://openmessage?chat_id=" + chatId;
                titleOrFirstName = extractStringField(chat, "title");
            }

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

            View nameView = null;
            View statusView = null;
            for (View view : candidates) {
                CharSequence existing = getCurrentText(view);
                if (candidates.size() > 1 && titleOrFirstName != null && !titleOrFirstName.isEmpty()
                        && existing != null && existing.toString().startsWith(titleOrFirstName)) {
                    nameView = view;
                    continue;
                }
                statusView = view;
            }

            if (statusView == null) {
                return;
            }

            ViewParent parent = statusView.getParent();
            if (!(parent instanceof ViewGroup)) {
                return;
            }

            ViewGroup container = (ViewGroup) parent;
            String labelText = dcId > 0
                    ? ("ID: " + displayId + ", DC: " + dcId)
                    : ("ID: " + displayId);

            TextView idDcView = new TextView(context);
            idDcView.setText(labelText);
            idDcView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            idDcView.setTextColor(Color.parseColor("#A0A6AE"));
            idDcView.setSingleLine(true);
            idDcView.setIncludeFontPadding(false);
            idDcView.setGravity(Gravity.CENTER_HORIZONTAL);
            idDcView.setPadding(0, 0, 0, 0);
            idDcView.setClickable(true);
            idDcView.setFocusable(true);

            final View themeSourceView = nameView != null ? nameView : statusView;
            idDcView.setOnClickListener(v -> showNativeOrFallbackPopup(profileActivity, v, themeSourceView, displayId, deepLink, labelText, dcId > 0));

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.LEFT
            );
            container.addView(idDcView, lp);
            idDcView.bringToFront();

            final View anchor = statusView;
            final int yOffset = -dp(context, 2);
            final float minLeftMargin = dp(context, 16);
            final float centerTolerance = dp(context, 24);
            final int[] anchorLoc = new int[2];
            final int[] containerLoc = new int[2];

            ViewTreeObserver.OnPreDrawListener syncListener = () -> {
                boolean visible = anchor.getVisibility() == View.VISIBLE && anchor.getAlpha() > 0.05f;
                idDcView.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
                idDcView.setClickable(visible);
                if (visible) {
                    anchor.getLocationInWindow(anchorLoc);
                    container.getLocationInWindow(containerLoc);
                    float anchorVisualX = (anchorLoc[0] - containerLoc[0]) + anchor.getPaddingLeft();
                    float anchorTextW = getAnchorTextWidth(anchor);
                    float anchorMid = anchorVisualX + anchorTextW / 2f;
                    float containerMid = container.getWidth() / 2f;
                    boolean isLeftAligned = Math.abs(anchorMid - containerMid) > centerTolerance;
                    float targetX = isLeftAligned
                            ? Math.max(minLeftMargin, anchorVisualX)
                            : ((container.getWidth() - idDcView.getWidth()) / 2f);
                    float targetY = anchor.getY() + anchor.getHeight() * anchor.getScaleY() + yOffset;
                    idDcView.setX(Math.max(0f, targetX));
                    idDcView.setY(targetY);
                    idDcView.setTextColor(resolveAdaptiveTextColor(themeSourceView, anchor));
                    idDcView.setAlpha(anchor.getAlpha());
                    idDcView.setScaleX(anchor.getScaleX());
                    idDcView.setScaleY(anchor.getScaleY());
                }
                return true;
            };
            anchor.getViewTreeObserver().addOnPreDrawListener(syncListener);

            logD("Injected idDcView: '" + labelText + "'");
        } catch (Throwable t) {
            logE("attachProfileInfo error", t);
        }
    }

    private static void showNativeOrFallbackPopup(
            Object profileActivity,
            View anchor,
            View themeSourceView,
            String displayId,
            String deepLink,
            String fullLabel,
            boolean hasDc
    ) {
        Context context = anchor.getContext();
        if (makeOptionsMethod != null && addItemMethod != null && showOptionsMethod != null) {
            try {
                Object options = makeOptionsMethod.invoke(null, profileActivity, anchor);
                if (options != null) {
                    int copyIcon = resolveDrawableId(context, "msg_copy");
                    int linkIcon = resolveDrawableId(context, "msg_link");
                    if (linkIcon == 0) linkIcon = resolveDrawableId(context, "msg_link2");

                    invokeAddItem(options, copyIcon, "Copy ID",
                            () -> copyToClipboard(context, "Telegram ID", displayId));
                    invokeAddItem(options, linkIcon, "Copy Link",
                            () -> copyToClipboard(context, "Telegram Link", deepLink));
                    if (hasDc) {
                        invokeAddItem(options, copyIcon, "Copy ID & DC",
                                () -> copyToClipboard(context, "Telegram Profile Info", fullLabel));
                    }
                    showOptionsMethod.invoke(options);
                    logD("Showed native Telegram ItemOptions popup");
                    return;
                }
            } catch (Throwable t) {
                logE("Native ItemOptions failed, using fallback", t);
            }
        }

        // Fallback if DexKit hasn't finished yet
        int bgColor = resolvePopupBackgroundColor(profileActivity, context, themeSourceView);
        boolean isDark = isColorDark(bgColor);
        int textColor = isDark ? Color.WHITE : Color.parseColor("#222222");

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setMinimumWidth(dp(context, 210));
        card.setPadding(0, dp(context, 6), 0, dp(context, 6));

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(isDark ? Color.parseColor("#EB1F2126") : Color.parseColor("#F5FFFFFF"));
        cardBg.setCornerRadius(dp(context, 16));
        card.setBackground(cardBg);

        PopupWindow popup = new PopupWindow(
                card,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true
        );
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setElevation(dp(context, 14));
        popup.setOutsideTouchable(true);

        card.addView(buildFallbackRow(context, textColor, "Copy ID", v -> {
            copyToClipboard(context, "Telegram ID", displayId);
            popup.dismiss();
        }));
        card.addView(buildFallbackRow(context, textColor, "Copy Link", v -> {
            copyToClipboard(context, "Telegram Link", deepLink);
            popup.dismiss();
        }));
        if (hasDc) {
            card.addView(buildFallbackRow(context, textColor, "Copy ID & DC", v -> {
                copyToClipboard(context, "Telegram Profile Info", fullLabel);
                popup.dismiss();
            }));
        }
        popup.showAsDropDown(anchor, 0, dp(context, 4));
    }

    private static void invokeAddItem(Object options, int iconRes, String text, Runnable action) throws Exception {
        if (addItemMethod.getParameterTypes().length == 4) {
            addItemMethod.invoke(options, iconRes, text, action, false);
        } else {
            addItemMethod.invoke(options, iconRes, text, action);
        }
    }

    private static int resolveDrawableId(Context context, String name) {
        return context.getResources().getIdentifier(name, "drawable", context.getPackageName());
    }

    private static TextView buildFallbackRow(Context context, int textColor, String label, View.OnClickListener onClick) {
        TextView tv = new TextView(context);
        tv.setText(label);
        tv.setTextColor(textColor);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
        tv.setMinimumHeight(dp(context, 48));
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setPadding(dp(context, 20), dp(context, 10), dp(context, 24), dp(context, 10));
        tv.setOnClickListener(onClick);
        return tv;
    }

    private static float getAnchorTextWidth(View view) {
        try {
            Method m = view.getClass().getMethod("getTextWidth");
            Object res = m.invoke(view);
            if (res instanceof Number && ((Number) res).floatValue() > 0f) {
                return ((Number) res).floatValue();
            }
        } catch (Throwable ignored) {
        }
        CharSequence text = getCurrentText(view);
        if (text != null && text.length() > 0) {
            try {
                Method getPaint = view.getClass().getMethod("getPaint");
                Object paint = getPaint.invoke(view);
                if (paint instanceof Paint) {
                    return ((Paint) paint).measureText(text, 0, text.length());
                }
            } catch (Throwable ignored) {
            }
            Paint fallback = new Paint();
            fallback.setTextSize(TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, 14f, view.getResources().getDisplayMetrics()));
            return fallback.measureText(text, 0, text.length());
        }
        return view.getWidth();
    }

    private static int resolveAdaptiveTextColor(View themeSourceView, View anchor) {
        int baseColor = getViewTextColor(themeSourceView, 0);
        if (baseColor == 0) {
            baseColor = getViewTextColor(anchor, Color.parseColor("#A0A6AE"));
        }
        return (baseColor & 0x00FFFFFF) | 0xB8000000;
    }

    private static int resolvePopupBackgroundColor(Object profileActivity, Context context, View themeSourceView) {
        try {
            Object fragmentView = XposedHelpers.getObjectField(profileActivity, "fragmentView");
            if (fragmentView instanceof View) {
                Drawable bg = ((View) fragmentView).getBackground();
                if (bg instanceof ColorDrawable) {
                    int c = ((ColorDrawable) bg).getColor();
                    if (Color.alpha(c) > 200) {
                        return isColorDark(c) ? shiftColor(c, 1.18f) : Color.WHITE;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        int textColor = getViewTextColor(themeSourceView, 0);
        if (textColor != 0) {
            return !isColorDark(textColor) ? Color.parseColor("#1F2126") : Color.WHITE;
        }
        int nightMask = context.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return nightMask == android.content.res.Configuration.UI_MODE_NIGHT_YES
                ? Color.parseColor("#1F2126") : Color.WHITE;
    }

    private static int getViewTextColor(View view, int fallback) {
        if (view == null) {
            return fallback;
        }
        try {
            Method getPaint = view.getClass().getMethod("getPaint");
            Object paint = getPaint.invoke(view);
            if (paint instanceof Paint) {
                return ((Paint) paint).getColor();
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    private static boolean isColorDark(int color) {
        double lum = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255.0;
        return lum < 0.5;
    }

    private static int shiftColor(int color, float factor) {
        int r = Math.min(255, Math.round(Color.red(color) * factor + 8));
        int g = Math.min(255, Math.round(Color.green(color) * factor + 8));
        int b = Math.min(255, Math.round(Color.blue(color) * factor + 8));
        return Color.rgb(r, g, b);
    }

    private static void copyToClipboard(Context context, String label, String text) {
        try {
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText(label, text));
                Toast.makeText(context, "Copied: " + text, Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            logE("copyToClipboard failed", t);
        }
    }

    private static int dp(Context context, float dp) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, context.getResources().getDisplayMetrics()));
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

    private static Object findEntityById(Object messagesController, long id, String entityClassName, ClassLoader cl) {
        if (messagesController == null) {
            return null;
        }
        Class<?> entityCls = XposedHelpers.findClassIfExists(entityClassName, cl);
        if (entityCls == null) {
            return null;
        }
        for (Method m : messagesController.getClass().getMethods()) {
            Class<?>[] params = m.getParameterTypes();
            if (params.length == 1
                    && (params[0] == Long.class || params[0] == long.class)
                    && entityCls.isAssignableFrom(m.getReturnType())) {
                try {
                    Object res = m.invoke(messagesController, id);
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

    private static int extractDcId(Object entity) {
        if (entity == null) {
            return 0;
        }
        try {
            Object photo = XposedHelpers.getObjectField(entity, "photo");
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
