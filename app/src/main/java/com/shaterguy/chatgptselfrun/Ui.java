package com.shaterguy.chatgptselfrun;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
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
import android.widget.Space;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputLayout;

final class Ui {
    static final int DEST_RUN = 0;
    static final int DEST_HISTORY = 1;
    static final int DEST_TOOLS = 2;

    private static final int WIDTH_MEDIUM_DP = 600;
    private static final int WIDTH_EXPANDED_DP = 840;
    private static final int PAGE_MAX_DP = 1120;
    private static final int READING_MAX_DP = 760;

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
            float radius = dp(context, 14);
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

    static int windowWidthDp(Context context) {
        int widthPx = context.getResources().getDisplayMetrics().widthPixels;
        if (context instanceof Activity && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                widthPx = ((Activity) context).getWindowManager().getCurrentWindowMetrics().getBounds().width();
            } catch (Throwable ignored) { }
        }
        float density = Math.max(0.1f, context.getResources().getDisplayMetrics().density);
        return Math.round(widthPx / density);
    }

    static boolean isMedium(Context context) {
        return windowWidthDp(context) >= WIDTH_MEDIUM_DP;
    }

    static boolean isExpanded(Context context) {
        return windowWidthDp(context) >= WIDTH_EXPANDED_DP;
    }

    static SelectionField selection(Context context, String label) {
        return new SelectionField(context, label);
    }

    static TextView title(Context context, String text) {
        TextView view = text(context, text, 28f, Typeface.BOLD);
        view.setPadding(0, 0, 0, dp(context, 2));
        return view;
    }

    static TextView headline(Context context, String text) {
        TextView view = text(context, text, 22f, Typeface.BOLD);
        view.setPadding(0, 0, 0, dp(context, 4));
        return view;
    }

    static TextView subtitle(Context context, String text) {
        TextView view = body(context, text);
        view.setTextSize(13.5f);
        view.setTextColor(onSurfaceVariant(context));
        view.setPadding(0, 0, 0, dp(context, 8));
        return view;
    }

    static TextView section(Context context, String text) {
        TextView view = text(context, text, 15f, Typeface.BOLD);
        view.setTextColor(onSurfaceVariant(context));
        view.setPadding(0, dp(context, 18), 0, dp(context, 6));
        return view;
    }

    static TextView cardTitle(Context context, String text) {
        return headline(context, text);
    }

    static TextView body(Context context, String text) {
        TextView view = text(context, text, 14.5f, Typeface.NORMAL);
        view.setLineSpacing(dp(context, 2), 1.08f);
        view.setPadding(0, dp(context, 2), 0, dp(context, 4));
        view.setTextIsSelectable(true);
        return view;
    }

    static TextView muted(Context context, String text) {
        TextView view = body(context, text);
        view.setTextSize(12.5f);
        view.setTextColor(onSurfaceVariant(context));
        return view;
    }

    private static TextView text(Context context, String value, float size, int style) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTypeface(Typeface.create("sans", style));
        view.setTextColor(onSurface(context));
        return view;
    }

    static TextView statusPill(Context context, String text) {
        TextView view = text(context, text, 12.5f, Typeface.BOLD);
        view.setTextColor(themeColor(context, com.google.android.material.R.attr.colorOnPrimaryContainer, Color.BLACK));
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(context, 12), dp(context, 7), dp(context, 12), dp(context, 7));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(themeColor(context, com.google.android.material.R.attr.colorPrimaryContainer, 0xFFDCE6FF));
        bg.setCornerRadius(dp(context, 99));
        view.setBackground(bg);
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

    static Button textButton(Context context, String text, View.OnClickListener listener) {
        MaterialButton button = materialButton(context, text, listener,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setStrokeWidth(0);
        return button;
    }

    private static MaterialButton materialButton(Context context, String text, View.OnClickListener listener,
                                                  int styleAttr) {
        MaterialButton button = new MaterialButton(context, null, styleAttr);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(14f);
        button.setMinHeight(dp(context, 48));
        button.setMinimumHeight(dp(context, 48));
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

    static LinearLayout actionStrip(Context context, View... children) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        for (int i = 0; i < children.length; i++) {
            View child = children[i];
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) params.setMarginStart(dp(context, 8));
            row.addView(child, params);
        }
        return row;
    }

    static MaterialCardView card(Context context, View... children) {
        return surface(context, false, children);
    }

    static MaterialCardView heroSurface(Context context, View... children) {
        return surface(context, true, children);
    }

    private static MaterialCardView surface(Context context, boolean hero, View... children) {
        MaterialCardView card = new MaterialCardView(context);
        card.setRadius(dp(context, hero ? 26 : 18));
        card.setCardElevation(0);
        card.setUseCompatPadding(false);
        card.setStrokeWidth(hero ? 0 : dp(context, 1));
        card.setStrokeColor(themeColor(context, com.google.android.material.R.attr.colorOutlineVariant, 0xFFE0E2EC));
        card.setCardBackgroundColor(hero
                ? themeColor(context, com.google.android.material.R.attr.colorPrimaryContainer, 0xFFE8EEFF)
                : themeColor(context, com.google.android.material.R.attr.colorSurface, Color.WHITE));

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        int horizontal = hero ? 20 : 16;
        int vertical = hero ? 20 : 14;
        content.setPadding(dp(context, horizontal), dp(context, vertical), dp(context, horizontal), dp(context, vertical));
        for (int i = 0; i < children.length; i++) {
            View child = children[i];
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) params.topMargin = dp(context, 6);
            content.addView(child, params);
        }
        card.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return card;
    }

    static LinearLayout topBar(Context context, String title, String subtitle, View action) {
        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, dp(context, 4), 0, dp(context, 14));
        LinearLayout text = new LinearLayout(context);
        text.setOrientation(LinearLayout.VERTICAL);
        text.addView(title(context, title));
        if (subtitle != null && !subtitle.isEmpty()) text.addView(muted(context, subtitle));
        bar.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (action != null) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMarginStart(dp(context, 12));
            bar.addView(action, params);
        }
        return bar;
    }

    static LinearLayout keyValue(Context context, String key, String value) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        TextView keyView = muted(context, key);
        keyView.setTextIsSelectable(false);
        TextView valueView = body(context, value == null || value.isEmpty() ? "-" : value);
        row.addView(keyView, new LinearLayout.LayoutParams(dp(context, 116), ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        valueParams.setMarginStart(dp(context, 10));
        row.addView(valueView, valueParams);
        return row;
    }

    static View divider(Context context) {
        View divider = new View(context);
        divider.setBackgroundColor(themeColor(context, com.google.android.material.R.attr.colorOutlineVariant, 0xFFE0E2EC));
        divider.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1)));
        return divider;
    }

    static LinearLayout listItem(Context context, String eyebrow, String title, String supporting,
                                 View.OnClickListener listener) {
        LinearLayout item = new LinearLayout(context);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setClickable(listener != null);
        item.setFocusable(listener != null);
        item.setPadding(dp(context, 4), dp(context, 14), dp(context, 4), dp(context, 14));
        if (listener != null) item.setOnClickListener(listener);
        if (eyebrow != null && !eyebrow.isEmpty()) {
            TextView e = muted(context, eyebrow);
            e.setTextIsSelectable(false);
            item.addView(e);
        }
        TextView t = text(context, title, 16f, Typeface.BOLD);
        t.setTextIsSelectable(false);
        item.addView(t);
        if (supporting != null && !supporting.isEmpty()) {
            TextView s = muted(context, supporting);
            s.setTextIsSelectable(false);
            s.setMaxLines(3);
            item.addView(s);
        }
        return item;
    }

    static LinearLayout settingsRow(Context context, String title, String supporting, View action) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(context, 4), dp(context, 10), dp(context, 4), dp(context, 10));
        LinearLayout text = new LinearLayout(context);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView t = Ui.text(context, title, 15f, Typeface.BOLD);
        t.setTextIsSelectable(false);
        text.addView(t);
        if (supporting != null && !supporting.isEmpty()) {
            TextView s = muted(context, supporting);
            s.setTextIsSelectable(false);
            text.addView(s);
        }
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (action != null) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMarginStart(dp(context, 12));
            row.addView(action, params);
        }
        return row;
    }

    static LinearLayout twoPane(Context context, View primary, View secondary) {
        LinearLayout panes = new LinearLayout(context);
        panes.setOrientation(isExpanded(context) ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        if (isExpanded(context)) {
            panes.addView(primary, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.05f));
            LinearLayout.LayoutParams secondaryParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.95f);
            secondaryParams.setMarginStart(dp(context, 20));
            panes.addView(secondary, secondaryParams);
        } else {
            panes.addView(primary, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams secondaryParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            secondaryParams.topMargin = dp(context, 16);
            panes.addView(secondary, secondaryParams);
        }
        return panes;
    }

    static LinearLayout page(Context context) {
        LinearLayout page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        int horizontal = isMedium(context) ? 28 : 18;
        page.setPadding(dp(context, horizontal), dp(context, 14), dp(context, horizontal), dp(context, 24));
        return page;
    }

    static void styleInput(Context context, EditText editor) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(themeColor(context, com.google.android.material.R.attr.colorSurfaceVariant, 0xFFF0F1FA));
        background.setCornerRadius(dp(context, 14));
        background.setStroke(dp(context, 1), themeColor(context, com.google.android.material.R.attr.colorOutline, 0xFF74777F));
        editor.setBackground(background);
        editor.setTextColor(onSurface(context));
        editor.setHintTextColor(onSurfaceVariant(context));
        editor.setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 14));
        editor.setMinHeight(dp(context, 56));
    }

    static void setPrimaryContent(Activity activity, View content, int destination) {
        setRootContent(activity, primaryScaffold(activity, content, destination), false);
    }

    static void setContent(Activity activity, View content) {
        setRootContent(activity, content, true);
    }

    private static View primaryScaffold(Activity activity, View content, int destination) {
        LinearLayout shell = new LinearLayout(activity);
        shell.setBackgroundColor(surfaceColor(activity));
        if (isMedium(activity)) {
            shell.setOrientation(LinearLayout.HORIZONTAL);
            shell.addView(navigationRail(activity, destination),
                    new LinearLayout.LayoutParams(dp(activity, 92), ViewGroup.LayoutParams.MATCH_PARENT));
            shell.addView(content, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        } else {
            shell.setOrientation(LinearLayout.VERTICAL);
            shell.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            shell.addView(bottomNavigation(activity, destination),
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return shell;
    }

    private static LinearLayout navigationRail(Activity activity, int selected) {
        LinearLayout rail = new LinearLayout(activity);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        rail.setPadding(dp(activity, 8), dp(activity, 16), dp(activity, 8), dp(activity, 12));
        TextView mark = statusPill(activity, "SR");
        mark.setTextSize(13f);
        rail.addView(mark, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Space spacer = new Space(activity);
        rail.addView(spacer, new LinearLayout.LayoutParams(1, dp(activity, 22)));
        rail.addView(navButton(activity, "실행", DEST_RUN, selected), navRailParams(activity));
        rail.addView(navButton(activity, "이력", DEST_HISTORY, selected), navRailParams(activity));
        rail.addView(navButton(activity, "도구", DEST_TOOLS, selected), navRailParams(activity));
        return rail;
    }

    private static LinearLayout.LayoutParams navRailParams(Context context) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(context, 6);
        return params;
    }

    private static LinearLayout bottomNavigation(Activity activity, int selected) {
        LinearLayout nav = new LinearLayout(activity);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(dp(activity, 8), dp(activity, 5), dp(activity, 8), dp(activity, 6));
        nav.setBackgroundColor(surfaceColor(activity));
        nav.addView(navButton(activity, "실행", DEST_RUN, selected), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        nav.addView(navButton(activity, "이력", DEST_HISTORY, selected), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        nav.addView(navButton(activity, "도구", DEST_TOOLS, selected), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return nav;
    }

    private static Button navButton(Activity activity, String label, int destination, int selected) {
        MaterialButton button = materialButton(activity, label,
                v -> navigate(activity, destination),
                destination == selected
                        ? com.google.android.material.R.attr.materialButtonTonalStyle
                        : com.google.android.material.R.attr.materialButtonOutlinedStyle);
        if (destination != selected) button.setStrokeWidth(0);
        button.setEnabled(destination != selected);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        return button;
    }

    private static void navigate(Activity activity, int destination) {
        Class<?> target = destination == DEST_RUN ? MainActivity.class
                : destination == DEST_HISTORY ? SelfRunHistoryActivity.class
                : SelfRunLogMenuActivity.class;
        if (target.equals(activity.getClass())) return;
        Intent intent = new Intent(activity, target)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        activity.startActivity(intent);
    }

    private static void setRootContent(Activity activity, View content, boolean constrain) {
        decorateTree(activity, content);
        int surface = surfaceColor(activity);
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
            if (constrain) applyAdaptiveContentWidth(activity, shell, content);
            return insets;
        });
        if (constrain) {
            shell.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                    applyAdaptiveContentWidth(activity, shell, content));
        }
        activity.setContentView(shell);
        shell.requestApplyInsets();
    }

    private static void applyAdaptiveContentWidth(Context context, FrameLayout shell, View content) {
        int availableWidth = Math.max(0, shell.getWidth() - shell.getPaddingLeft() - shell.getPaddingRight());
        if (availableWidth <= 0) return;
        int widthDp = windowWidthDp(context);
        int targetWidth = ViewGroup.LayoutParams.MATCH_PARENT;
        if (widthDp >= WIDTH_EXPANDED_DP) {
            targetWidth = Math.min(availableWidth - dp(context, 64), dp(context, READING_MAX_DP));
        } else if (widthDp >= WIDTH_MEDIUM_DP) {
            targetWidth = Math.min(availableWidth - dp(context, 48), dp(context, PAGE_MAX_DP));
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

    private static int onSurface(Context context) {
        return themeColor(context, com.google.android.material.R.attr.colorOnSurface, Color.BLACK);
    }

    private static int onSurfaceVariant(Context context) {
        return themeColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF5F6368);
    }

    private static int surfaceColor(Context context) {
        return themeColor(context, com.google.android.material.R.attr.colorSurface, Color.WHITE);
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
