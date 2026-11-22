package com.alradhy.worldwindow.infinity;

import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.View;

/** Keeps the pointer out of full-screen playback between mouse movements. */
final class QuietPointer {
    private final View target;
    private final Runnable hide=this::clearPointer;

    QuietPointer(View target){this.target=target;}

    void motion(MotionEvent event){
        if(!event.isFromSource(InputDevice.SOURCE_MOUSE))return;
        target.setPointerIcon(PointerIcon.getSystemIcon(target.getContext(),PointerIcon.TYPE_ARROW));
        target.removeCallbacks(hide);
        target.postDelayed(hide,3_000);
    }

    void reset(){
        target.removeCallbacks(hide);
        clearPointer();
    }

    private void clearPointer(){
        target.setPointerIcon(PointerIcon.getSystemIcon(target.getContext(),PointerIcon.TYPE_NULL));
    }
}
