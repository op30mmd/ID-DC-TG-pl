package com.tgmod.profileinfo;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
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
import android.widget.ImageView;
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
 * and supergroups/channels, with a Telegram-styled popup menu (Copy, Copy Link, Copy ID & DC).
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
            idDcView.setOnClickListener(v -> showTelegramPopup(profileActivity, v, displayId, deepLink, labelText, dcId > 0, cl));

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.LEFT
            );
            container.addView(idDcView, lp);

            idDcView.bringToFront();
            final View anchor = statusView;
            final int yOffset = -dp(context, 2);
            final float centerTolerance = dp(context, 24);
            final boolean[] loggedOnce = new boolean[]{false};
            ViewTreeObserver.OnPreDrawListener syncListener = () -> {
                boolean visible = anchor.getVisibility() == View.VISIBLE && anchor.getAlpha() > 0.05f;
                idDcView.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
                idDcView.setClickable(visible);
                if (visible) {
                    float anchorX = anchor.getX();
                    float anchorTextW = getAnchorTextWidth(anchor);
                    float anchorMid = anchorX + anchorTextW / 2f;
                    float containerMid = container.getWidth() / 2f;
                    boolean isLeftAligned = Math.abs(anchorMid - containerMid) > centerTolerance;
                    float targetX = isLeftAligned
                            ? (anchorX + anchor.getPaddingLeft())
                            : ((container.getWidth() - idDcView.getWidth()) / 2f);
                    float targetY = anchor.getY() + anchor.getHeight() * anchor.getScaleY() + yOffset;
                    idDcView.setX(Math.max(0f, targetX));
                    idDcView.setY(targetY);
                    idDcView.setAlpha(anchor.getAlpha());
                    idDcView.setScaleX(anchor.getScaleX());
                    idDcView.setScaleY(anchor.getScaleY());
                    if (!loggedOnce[0] && container.getWidth() > 0 && idDcView.getWidth() > 0) {
                        loggedOnce[0] = true;
                        logD("Layout sync: anchorX=" + anchorX + ", anchorY=" + anchor.getY()
                                + ", anchorH=" + anchor.getHeight() + ", textW=" + anchorTextW
                                + ", containerW=" + container.getWidth() + ", idDcW=" + idDcView.getWidth()
                                + ", isLeft=" + isLeftAligned + ", targetX=" + targetX + ", targetY=" + targetY);
                    }
                }
                return true;
            };
            anchor.getViewTreeObserver().addOnPreDrawListener(syncListener);

            logD("Injected idDcView: '" + labelText + "'");
        } catch (Throwable t) {
            logE("attachProfileInfo error", t);
        }
    }

    private static void showTelegramPopup(
            Object profileActivity,
            View anchor,
            String displayId,
            String deepLink,
            String fullLabel,
            boolean hasDc,
            ClassLoader cl
    ) {
        Context context = anchor.getContext();

        // Try Telegram's native ItemOptions popup if un-obfuscated in this build
        if (tryShowNativeItemOptions(profileActivity, anchor, displayId, deepLink, fullLabel, hasDc, cl)) {
            return;
        }

        // Fallback: Telegram-styled popup matching ActionBarPopupWindow geometry, icons, and ripple
        boolean isDark = isDarkTheme(context);
        int bgColor = isDark ? Color.parseColor("#212429") : Color.WHITE;
        int textColor = isDark ? Color.WHITE : Color.parseColor("#222222");
        int iconColor = isDark ? Color.parseColor("#B0B5BD") : Color.parseColor("#6D737B");

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setMinimumWidth(dp(context, 210));
        card.setPadding(0, dp(context, 8), 0, dp(context, 8));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(context, 12));
        card.setBackground(bg);

        TextView header = new TextView(context);
        header.setText(displayId);
        header.setTextColor(textColor);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setPadding(dp(context, 18), dp(context, 8), dp(context, 18), dp(context, 10));
        card.addView(header);

        PopupWindow popup = new PopupWindow(
                card,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true
        );
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setElevation(dp(context, 12));
        popup.setOutsideTouchable(true);

        card.addView(buildTelegramRow(context, createCopyIcon(context, iconColor), textColor, "Copy", v -> {
            copyToClipboard(context, "Telegram ID", displayId);
            popup.dismiss();
        }));

        card.addView(buildTelegramRow(context, createLinkIcon(context, iconColor), textColor, "Copy Link", v -> {
            copyToClipboard(context, "Telegram Link", deepLink);
            popup.dismiss();
        }));

        if (hasDc) {
            card.addView(buildTelegramRow(context, createCopyIcon(context, iconColor), textColor, "Copy ID & DC", v -> {
                copyToClipboard(context, "Telegram Profile Info", fullLabel);
                popup.dismiss();
            }));
        }

        popup.showAsDropDown(anchor, 0, dp(context, 4), Gravity.CENTER_HORIZONTAL);
    }

    private static boolean tryShowNativeItemOptions(
            Object profileActivity,
            View anchor,
            String displayId,
            String deepLink,
            String fullLabel,
            boolean hasDc,
            ClassLoader cl
    ) {
        try {
            Class<?> itemOptionsCls = XposedHelpers.findClassIfExists("org.telegram.ui.Components.ItemOptions", cl);
            if (itemOptionsCls == null) {
                return false;
            }
            Object options = XposedHelpers.callStaticMethod(itemOptionsCls, "makeOptions", profileActivity, anchor);
            if (options == null) {
                return false;
            }
            Context context = anchor.getContext();
            XposedHelpers.callMethod(options, "add", 0, "Copy",
                    (Runnable) () -> copyToClipboard(context, "Telegram ID", displayId));
            XposedHelpers.callMethod(options, "add", 0, "Copy Link",
                    (Runnable) () -> copyToClipboard(context, "Telegram Link", deepLink));
            if (hasDc) {
                XposedHelpers.callMethod(options, "add", 0, "Copy ID & DC",
                        (Runnable) () -> copyToClipboard(context, "Telegram Profile Info", fullLabel));
            }
            XposedHelpers.callMethod(options, "show");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static LinearLayout buildTelegramRow(
            Context context,
            Drawable icon,
            int textColor,
            String label,
            View.OnClickListener onClick
    ) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(context, 18), dp(context, 11), dp(context, 24), dp(context, 11));
        row.setClickable(true);
        row.setFocusable(true);

        TypedValue outValue = new TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)) {
            row.setBackgroundResource(outValue.resourceId);
        }

        ImageView iv = new ImageView(context);
        iv.setImageDrawable(icon);
        LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(dp(context, 22), dp(context, 22));
        ivLp.rightMargin = dp(context, 18);
        row.addView(iv, ivLp);

        TextView tv = new TextView(context);
        tv.setText(label);
        tv.setTextColor(textColor);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
        row.addView(tv);

        row.setOnClickListener(onClick);
        return row;
    }

    private static Drawable createCopyIcon(Context context, int color) {
        return new Drawable() {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final float s = dp(context, 22) / 24f;
            {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.9f * s);
                p.setStrokeJoin(Paint.Join.ROUND);
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setColor(color);
            }
            @Override
            public void draw(Canvas c) {
                c.drawRoundRect(new RectF(8 * s, 8 * s, 19 * s, 20 * s), 2 * s, 2 * s, p);
                c.drawLine(5 * s, 16 * s, 5 * s, 5 * s, p);
                c.drawLine(5 * s, 5 * s, 15 * s, 5 * s, p);
            }
            @Override public void setAlpha(int alpha) { p.setAlpha(alpha); }
            @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
    }

    private static Drawable createLinkIcon(Context context, int color) {
        return new Drawable() {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final float s = dp(context, 22) / 24f;
            {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.9f * s);
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setColor(color);
            }
            @Override
            public void draw(Canvas c) {
                c.drawArc(new RectF(3 * s, 8 * s, 11 * s, 16 * s), 90, 180, false, p);
                c.drawLine(7 * s, 8 * s, 10 * s, 8 * s, p);
                c.drawLine(7 * s, 16 * s, 10 * s, 16 * s, p);
                c.drawArc(new RectF(13 * s, 8 * s, 21 * s, 16 * s), -90, 180, false, p);
                c.drawLine(14 * s, 8 * s, 17 * s, 8 * s, p);
                c.drawLine(14 * s, 16 * s, 17 * s, 16 * s, p);
                c.drawLine(8.5f * s, 12 * s, 15.5f * s, 12 * s, p);
            }
            @Override public void setAlpha(int alpha) { p.setAlpha(alpha); }
            @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
    }

    private static boolean isDarkTheme(Context context) {
        int nightMask = context.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return nightMask == android.content.res.Configuration.UI_MODE_NIGHT_YES;
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
                if (paint instanceof android.graphics.Paint) {
                    return ((android.graphics.Paint) paint).measureText(text, 0, text.length());
                }
            } catch (Throwable ignored) {
            }
            android.graphics.Paint fallback = new android.graphics.Paint();
            fallback.setTextSize(TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP, 14f, view.getResources().getDisplayMetrics()));
            return fallback.measureText(text, 0, text.length());
        }
        return view.getWidth();
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
