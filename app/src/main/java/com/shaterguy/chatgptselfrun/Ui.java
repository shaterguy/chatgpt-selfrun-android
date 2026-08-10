package com.shaterguy.chatgptselfrun;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
    private Ui() {}

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static TextView title(Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(24f);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setPadding(0, 0, 0, dp(context, 10));
        return view;
    }

    static TextView section(Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(17f);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setPadding(0, dp(context, 12), 0, dp(context, 4));
        return view;
    }

    static TextView body(Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(14f);
        view.setPadding(0, dp(context, 2), 0, dp(context, 4));
        view.setTextIsSelectable(true);
        return view;
    }

    static Button button(Context context, String text, View.OnClickListener listener) {
        Button button = new Button(context);
        button.setText(text);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        return button;
    }

    static LinearLayout row(Context context, View... children) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (View child : children) {
            row.addView(child, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        return row;
    }

    static void setContent(Activity activity, View content) {
        activity.setContentView(content);
    }
}
