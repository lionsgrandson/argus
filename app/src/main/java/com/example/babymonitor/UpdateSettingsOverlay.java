package com.example.babymonitor;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;

final class UpdateSettingsOverlay {
    private static final int VIEW_ID = 0x0B10C0DE;

    static void attach(Activity activity) {
        if (activity == null || activity.isFinishing() || !(activity instanceof MainActivity)) return;

        View decor = activity.getWindow().getDecorView();
        ViewGroup content = decor.findViewById(android.R.id.content);
        if (content == null) return;

        hideFirstImage(content);

        if (content.findViewById(VIEW_ID) != null) return;

        ImageButton settings = new ImageButton(activity);
        settings.setId(VIEW_ID);
        settings.setImageResource(android.R.drawable.ic_menu_preferences);
        settings.setContentDescription("הגדרות");
        settings.setBackground(new ColorDrawable(Color.TRANSPARENT));
        settings.setPadding(dp(activity, 10), dp(activity, 10), dp(activity, 10), dp(activity, 10));
        settings.setOnClickListener(v -> activity.startActivity(new Intent(activity, SettingsActivity.class)));

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(activity, 48), dp(activity, 48));
        params.gravity = Gravity.TOP | Gravity.RIGHT;
        params.topMargin = dp(activity, 6);
        params.rightMargin = dp(activity, 8);

        if (content instanceof FrameLayout) {
            ((FrameLayout) content).addView(settings, params);
        } else {
            FrameLayout wrapper = new FrameLayout(activity);
            View current = content.getChildCount() > 0 ? content.getChildAt(0) : null;
            if (current != null) {
                content.removeView(current);
                wrapper.addView(current, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                ));
            }
            wrapper.addView(settings, params);
            content.addView(wrapper, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));
        }
    }

    private static boolean hideFirstImage(View view) {
        if (view instanceof ImageView) {
            view.setVisibility(View.GONE);
            return true;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (hideFirstImage(group.getChildAt(i))) return true;
            }
        }
        return false;
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private UpdateSettingsOverlay() {}
}
