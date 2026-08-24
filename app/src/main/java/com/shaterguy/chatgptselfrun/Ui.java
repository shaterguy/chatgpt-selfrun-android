package com.shaterguy.chatgptselfrun;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputLayout;

final class Ui {
    private static final int WIDTH_MEDIUM_DP = 600;
    private static final int WIDTH_EXPANDED_DP = 840;
    private static final int EXPANDED_CONTENT_MAX_DP = 760;

    private Ui() {}

    interface OnSelectionChangedListener {
        void onSelectionChanged(int position);
    }

    static final class SelectionField extends TextInputLayout {
        private final MaterialAutoCompleteTextView input;
        private String[] items = new String[0];
        private int selectedPosition;
        private OnSelectionChangedListener selectionChangedListener;

        SelectionField(Context context, String label) {
            super(context, null, com.google.android.material.R.attr.textInputOutlinedExposedDropdownMenuStyle);
            setHint(label);
            setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
            float radius = dp(context, 16);
            setBoxCornerRadii(radius, radius, radius, radius);
            setMinimumHeight(dp(context, 56));

            input = new MaterialAutoCompleteTextView(getContext());
            input.setRawInputType(android.text.InputType.TYPE_NULL);
            input.setKeyListener(null);
            input.setSingleLine(true);
            input.setMinHeight(dp(context, 56));
            input.setOnItemClickListener((parent, view, position, id) -> {
                if (items.length == 0) return;
                selectedPosition = Math.max(0, Math.min(items.length - 1, position));
                if (selectionChangedListener != null) {
                    selectionChangedListener.onSelectionChanged(selectedPosition);
                }
            });
            addView(input, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        void setItems(String[] values) {
            items = values == null ? new String[0] : values.clone();
            input.setSimpleItems(items);
            setSelection(Math.min(selectedPosition, Math.max(0, items.length - 1)));
        }

        void setSelection(int position) {
            if (items.length == 0) {
                selectedPosition = 0;
                input.setText("", false);
                return;
            }
            selectedPosition = Math.max(0, Math.min(items.length - 1, position));
            input.setText(items[selectedPosition], false);
        }

        int getSelectedItemPosition() {
            return selectedPosition;
        }

        void setOnSelectionChangedListener(OnSelectionChangedListener listener) {
            selectionChangedListener = listener;
        }

        @Override public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            if (input != null) input.setEnabled(enabled);
        }

        @Override public void clearFocus() {
            super.clearFocus();
            if (input != null) input.clearFocus();
        }
    }

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static SelectionField selection(Context context, String label) {
        return new SelectionField(context, label);
    }

    static TextView title(Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(28f);
        view.setTypeface(Typeface.create("sans", Typeface.BOLD));
        view.setTextColor(themeColor(context, com.google.android.material.R.attr.colorOnSurface, Color.BLACK));
        view.setPadding(0, 0, 0, dp(context, 4));
        return view;
    }

    static TextView subtitle(Context context, String text) {
        TextView view = body(context, text);
        view.setTextSize(13.5f);
        view.setTextColor(themeColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF5F6368));
        view.setPadding(0, 0, 0, dp(context, 12));
        return view;
    }

    static TextView section(Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(16f);
        view.setTypeface(Typeface.create("sans", Typeface.BOLD));
        view.setTextColor(themeColor(context, com.google.android.material.R.attr.colorOnSurface, Color.BLACK));
        view.setPadding(0, dp(context, 22), 0, dp(context, 8));
        return view;
    }

    static TextView cardTitle(Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(18f);
        view.setTypeface(Typeface.create("sans", Typeface.BOLD));
        view.setTextColor(themeColor(context, com.google.android.material.R.attr.colorOnSurface, Color.BLACK));
        view.setPadding(0, 0, 0, dp(context, 8));
        return view;
    }

    static TextView body(Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(14.5f);
        view.setLineSpacing(dp(context, 2), 1.08f);
        view.setPadding(0, dp(context, 2), 0, dp(context, 6));
        view.setTextColor(themeColor(context, com.google.android.material.R.attr.colorOnSurface, Color.BLACK));
        view.setTextIsSelectable(true);
        return view;
    }

    static TextView muted(Context context, String text) {
        TextView view = body(context, text);
        view.setTextSize(12.5f);
        view.setTextColor(themeColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF5F6368));
        return view;
    }

    static Button button(Context context, String text, View.OnClickListener listener) {
        return materialButton(context, text, listener, com.google.android.material.R.attr.materialButtonStyle);
    }

    static Button tonalButton(Context context, String text, View.OnClickListener listener) {
        return materialButton(context, text, listener, com.google.android.material.R.attr.materialButtonTonalStyle);
    }

    static Button outlinedButton(Context context, String text, View.OnClickListener listener) {
        return materialButton(context, text, listener, com.google.android.material.R.attr.materialButtonOutlinedStyle);
    }

    static Button dangerButton(Context context, String text, View.OnClickListener listener) {
        MaterialButton button = materialButton(context, text, listener,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        int error = themeColor(context, androidx.appcompat.R.attr.colorError, 0xFFBA1A1A);
        button.setTextColor(error);
        button.setStrokeColor(ColorStateList.valueOf(error));
        return button;
    }

    private static MaterialButton materialButton(Context context, String text, View.OnClickListener listener,
                                                  int styleAttr) {
        MaterialButton button = new MaterialButton(context, null, styleAttr);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(14f);
        button.setMinHeight(dp(context, 52));
        button.setMinimumHeight(dp(context, 52));
        button.setOnClickListener(listener);
        return button;
    }

    static LinearLayout row(Context context, View... children) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < children.length; i++) {
            View child = children[i];
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) params.setMarginStart(dp(context, 8));
            row.addView(child, params);
        }
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(0, dp(context, 4), 0, dp(context, 4));
        row.setLayoutParams(rowParams);
        return row;
    }

    static MaterialCardView card(Context context, View... children) {
        MaterialCardView card = new MaterialCardView(context);
        card.setRadius(dp(context, 22));
        card.setCardElevation(dp(context, 1));
        card.setUseCompatPadding(false);
        card.setStrokeWidth(dp(context, 1));
        card.setStrokeColor(themeColor(context, com.google.android.material.R.attr.colorOutlineVariant, 0xFFE0E2EC));
        card.setCardBackgroundColor(themeColor(context, com.google.android.material.R.attr.colorSurface, Color.WHITE));

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(context, 18), dp(context, 16), dp(context, 18), dp(context, 16));
        for (int i = 0; i < children.length; i++) {
            View child = children[i];
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) params.topMargin = dp(context, 4);
            content.addView(child, params);
        }
        card.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, dp(context, 5), 0, dp(context, 9));
        card.setLayoutParams(cardParams);
        return card;
    }

    static void styleInput(Context context, EditText editor) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(themeColor(context, com.google.android.material.R.attr.colorSurfaceVariant, 0xFFF0F1FA));
        background.setCornerRadius(dp(context, 16));
        background.setStroke(dp(context, 1),
                themeColor(context, com.google.android.material.R.attr.colorOutline, 0xFF74777F));
        editor.setBackground(background);
        editor.setTextColor(themeColor(context, com.google.android.material.R.attr.colorOnSurface, Color.BLACK));
        editor.setHintTextColor(themeColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF5F6368));
        editor.setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 14));
        editor.setMinHeight(dp(context, 56));
    }

    static void setContent(Activity activity, View content) {
        decorateTree(activity, content);
        int surface = themeColor(activity, com.google.android.material.R.attr.colorSurface, Color.WHITE);
        content.setBackgroundColor(surface);

        FrameLayout shell = new FrameLayout(activity);
        shell.setBackgroundColor(surface);
        shell.setClipToPadding(false);
        shell.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL));

        Window window = activity.getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
            window.setStatusBarColor(Color.TRANSPARENT);
            window.setNavigationBarColor(Color.TRANSPARENT);
        }
        shell.setOnApplyWindowInsetsListener((view, insets) -> {
            int insetLeft;
            int insetTop;
            int insetRight;
            int insetBottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets safe = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                insetLeft = safe.left;
                insetTop = safe.top;
                insetRight = safe.right;
                insetBottom = safe.bottom;
            } else {
                insetLeft = insets.getSystemWindowInsetLeft();
                insetTop = insets.getSystemWindowInsetTop();
                insetRight = insets.getSystemWindowInsetRight();
                insetBottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(insetLeft, insetTop, insetRight, insetBottom);
            applyAdaptiveContentWidth(activity, shell, content);
            return insets;
        });
        shell.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                applyAdaptiveContentWidth(activity, shell, content));
        activity.setContentView(shell);
        shell.requestApplyInsets();
    }

    private static void applyAdaptiveContentWidth(Context context, FrameLayout shell, View content) {
        int availableWidth = Math.max(0, shell.getWidth() - shell.getPaddingLeft() - shell.getPaddingRight());
        if (availableWidth <= 0) return;
        float density = context.getResources().getDisplayMetrics().density;
        int widthDp = Math.round(availableWidth / Math.max(0.1f, density));
        int targetWidth = ViewGroup.LayoutParams.MATCH_PARENT;
        if (widthDp >= WIDTH_EXPANDED_DP) {
            targetWidth = Math.min(availableWidth - dp(context, 64), dp(context, EXPANDED_CONTENT_MAX_DP));
        } else if (widthDp >= WIDTH_MEDIUM_DP) {
            targetWidth = availableWidth - dp(context, 48);
        }
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) content.getLayoutParams();
        if (params.width == targetWidth && params.gravity == (Gravity.TOP | Gravity.CENTER_HORIZONTAL)) return;
        params.width = targetWidth;
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        content.setLayoutParams(params);
    }

    private static void decorateTree(Context context, View view) {
        if (view instanceof MaterialAutoCompleteTextView) {
            view.setMinimumHeight(dp(context, 56));
        } else if (view instanceof EditText) {
            styleInput(context, (EditText) view);
        } else if (view instanceof Button) {
            view.setMinimumHeight(dp(context, 48));
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) decorateTree(context, group.getChildAt(i));
        }
    }

    private static int themeColor(Context context, int attr, int fallback) {
        TypedValue value = new TypedValue();
        if (!context.getTheme().resolveAttribute(attr, value, true)) return fallback;
        if (value.resourceId != 0) {
            try { return context.getColor(value.resourceId); } catch (Throwable ignored) { }
        }
        return value.data != 0 ? value.data : fallback;
    }
}
