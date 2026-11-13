package com.tgmod.profileinfo;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Obfuscation-immune LSPosed module that displays a clean "ID: <id>, DC: <dc>"
 * line right under the profile status/avatar in ProfileActivity for both users
 * and supergroups/channels, with a clean tap-to-copy popup menu.
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String LOG_TAG = "TG-ProfileInfo";
    private static final String TAG = "[TG-ProfileInfo] ";

    private static final Set<String> TARGET_PACKAGES = new HashSet<>(Arrays.asList(
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "org.telegram.messenger.beta"
    ));

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
                            attachProfileInfo(param.thisObject, (Context) param.args[0], cl);
                        }
                    }
            );
            logD("Hooked ProfileActivity.createView(Context)");
        } catch (Throwable t) {
            logE("Failed to hook ProfileActivity.createView(Context)", t);
        }
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
            String titleOrFirstName;

            if (userId != 0L) {
                Object user = findEntityById(messagesController, userId, "org.telegram.tgnet.TLRPC$User", cl);
                dcId = extractDcId(user);
                displayId = String.valueOf(userId);
                titleOrFirstName = extractStringField(user, "first_name");
            } else {
                Object chat = findEntityById(messagesController, chatId, "org.telegram.tgnet.TLRPC$Chat", cl);
                dcId = extractDcId(chat);
                boolean isChannelOrSupergroup = chat != null
                        && chat.getClass().getSimpleName().toLowerCase().contains("channel");
                displayId = isChannelOrSupergroup ? ("-100" + chatId) : String.valueOf(chatId);
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

            View statusView = null;
            for (View view : candidates) {
                CharSequence existing = getCurrentText(view);
                if (candidates.size() > 1 && titleOrFirstName != null && !titleOrFirstName.isEmpty()
                        && existing != null && existing.toString().startsWith(titleOrFirstName)) {
                    continue;
                }
                statusView = view;
            }

            if (statusView == null) {
                logD("No statusView found for id=" + displayId);
                return;
            }

            ViewParent parent = statusView.getParent();
            if (!(parent instanceof ViewGroup)) {
                logD("statusView parent is not a ViewGroup");
                return;
            }

            ViewGroup container = (ViewGroup) parent;
            String labelText = dcId > 0
                    ? ("ID: " + displayId + ", DC: " + dcId)
                    : ("ID: " + displayId);

            TextView idDcView = new TextView(context);
            idDcView.setText(labelText);
            idDcView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            idDcView.setTextColor(Color.parseColor("#9AA0A6"));
            idDcView.setSingleLine(true);
            idDcView.setGravity(Gravity.CENTER_HORIZONTAL);
            idDcView.setPadding(dp(context, 8), dp(context, 1), dp(context, 8), dp(context, 2));
            idDcView.setClickable(true);
            idDcView.setFocusable(true);
            idDcView.setOnClickListener(v -> showCopyPopup(v, displayId, labelText));

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.LEFT
            );
            container.addView(idDcView, lp);

            final View anchor = statusView;
            final int yOffset = dp(context, 2);
            ViewTreeObserver.OnPreDrawListener syncListener = () -> {
                if (anchor.getVisibility() != View.VISIBLE || anchor.getAlpha() <= 0.05f) {
                    idDcView.setVisibility(View.GONE);
                } else {
                    idDcView.setVisibility(View.VISIBLE);
                    float centerX = anchor.getX() + (anchor.getWidth() - idDcView.getWidth()) / 2f;
                    idDcView.setX(Math.max(0f, centerX));
                    idDcView.setY(anchor.getY() + anchor.getHeight() * anchor.getScaleY() + yOffset);
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

    private static void showCopyPopup(View anchor, String displayId, String fullLabel) {
        Context context = anchor.getContext();

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        int padH = dp(context, 18);
        int padV = dp(context, 14);
        card.setPadding(padH, padV, padH, padV);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#1E2229"));
        bg.setCornerRadius(dp(context, 14));
        card.setBackground(bg);

        TextView header = new TextView(context);
        header.setText(displayId);
        header.setTextColor(Color.WHITE);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setPadding(0, 0, 0, dp(context, 10));
        card.addView(header);

        PopupWindow popup = new PopupWindow(
                card,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true
        );
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setElevation(dp(context, 10));
        popup.setOutsideTouchable(true);

        card.addView(buildMenuItem(context, "Copy", v -> {
            copyToClipboard(context, "Telegram ID", displayId);
            popup.dismiss();
        }));

        card.addView(buildMenuItem(context, "Copy ID & DC", v -> {
            copyToClipboard(context, "Telegram Profile Info", fullLabel);
            popup.dismiss();
        }));

        popup.showAsDropDown(anchor, 0, dp(context, 4), Gravity.CENTER_HORIZONTAL);
    }

    private static TextView buildMenuItem(Context context, String label, View.OnClickListener onClick) {
        TextView item = new TextView(context);
        item.setText(label);
        item.setTextColor(Color.parseColor("#E6EDF3"));
        item.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        item.setPadding(0, dp(context, 10), dp(context, 24), dp(context, 10));
        item.setClickable(true);
        item.setFocusable(true);
        item.setOnClickListener(onClick);
        return item;
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
