package com.alradhy.worldwindow.infinity;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Focusable native options for the TV remote, keyboard and pointer. */
final class ActionPanel {
    private static final int GOLD=0xffdfb56f;
    private static final int CREAM=0xfff5f4e8;
    private final Context context;
    private final Dialog dialog;
    private final LinearLayout content;
    private View first;

    ActionPanel(Context context,String title){
        this.context=context;
        dialog=new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel=new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xff102831);
        panel.setPadding(dp(24),dp(24),dp(24),dp(20));
        TextView heading=text(title,26,CREAM);
        heading.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        panel.addView(heading,new LinearLayout.LayoutParams(-1,-2));
        ScrollView scroll=new ScrollView(context);
        scroll.setClipToPadding(false);
        scroll.setPadding(dp(2),dp(10),dp(2),dp(4));
        scroll.setDescendantFocusability(ScrollView.FOCUS_AFTER_DESCENDANTS);
        scroll.setFocusable(false);
        content=new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content,new ScrollView.LayoutParams(-1,-2));
        panel.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        dialog.setContentView(panel);
    }

    void section(String title){
        TextView label=text(title,15,GOLD);
        label.setPadding(dp(12),dp(14),dp(12),dp(6));
        if(Build.VERSION.SDK_INT>=28)label.setAccessibilityHeading(true);
        content.addView(label,new LinearLayout.LayoutParams(-1,-2));
    }

    void action(String title,Runnable command){
        TextView row=text(title,19,CREAM);
        row.setMinHeight(dp(48));
        row.setPadding(dp(14),dp(9),dp(14),dp(9));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setPointerIcon(PointerIcon.getSystemIcon(context,PointerIcon.TYPE_HAND));
        row.setOnHoverListener((view,event)->{
            if(event.getActionMasked()==MotionEvent.ACTION_HOVER_ENTER)view.requestFocus();
            return false;
        });
        StateListDrawable backgrounds=new StateListDrawable();
        backgrounds.addState(new int[]{android.R.attr.state_focused},fill(0xff28505c,GOLD));
        backgrounds.addState(new int[]{android.R.attr.state_pressed},fill(0xff28505c,GOLD));
        backgrounds.addState(new int[]{},fill(0xff17333e,Color.TRANSPARENT));
        row.setBackground(backgrounds);
        row.setOnClickListener(view->{dialog.dismiss();command.run();});
        LinearLayout.LayoutParams dimensions=new LinearLayout.LayoutParams(-1,-2);
        dimensions.bottomMargin=dp(6);
        content.addView(row,dimensions);
        if(first==null)first=row;
    }

    void show(){
        Window window=dialog.getWindow();
        if(window==null)return;
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(.55f);
        window.setGravity(Gravity.END|Gravity.TOP);
        dialog.show();
        int width=Math.min(dp(460),(int)(context.getResources().getDisplayMetrics().widthPixels*.56f));
        window.setLayout(width,WindowManager.LayoutParams.MATCH_PARENT);
        if(first!=null)first.requestFocus();
    }

    private TextView text(String title,int size,int color){
        TextView view=new TextView(context);
        view.setText(title);
        view.setTextSize(size);
        view.setTextColor(ColorStateList.valueOf(color));
        view.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        return view;
    }

    private GradientDrawable fill(int color,int line){
        GradientDrawable shape=new GradientDrawable();
        shape.setCornerRadius(dp(10));
        shape.setColor(color);
        shape.setStroke(dp(2),line);
        return shape;
    }

    private int dp(int value){
        return Math.round(value*context.getResources().getDisplayMetrics().density);
    }
}
