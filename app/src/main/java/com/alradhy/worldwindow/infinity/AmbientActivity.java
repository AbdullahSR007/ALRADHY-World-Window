package com.alradhy.worldwindow.infinity;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/** Full-screen Ambient Mode: the whole offline picture library, or one destination's pack. */
public final class AmbientActivity extends Activity {
    static final String EXTRA_SCENE="scene_id";
    static final String EXTRA_START="start";
    static final String EXTRA_ANCHOR_SCENE="anchor_scene";
    private AmbientView ambient;
    private float downX;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_FULLSCREEN|
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ambient=new AmbientView(this,getIntent().getStringExtra(EXTRA_SCENE),
            getIntent().getIntExtra(EXTRA_START,0));
        ambient.anchorScene=getIntent().getStringExtra(EXTRA_ANCHOR_SCENE);
        setContentView(ambient);
        ambient.requestFocus();
        ambient.start();
    }

    @Override public boolean onKeyDown(int key,KeyEvent event){
        switch(key){
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                ambient.step(1);return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                ambient.step(-1);return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                ambient.togglePause();return true;
            default:return super.onKeyDown(key,event);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event){
        if(event.getAction()==MotionEvent.ACTION_DOWN){downX=event.getX();return true;}
        if(event.getAction()==MotionEvent.ACTION_UP){
            float shift=event.getX()-downX;
            if(Math.abs(shift)>70)ambient.step(shift<0?1:-1);
            else ambient.togglePause();
            return true;
        }
        return true;
    }

    @Override public boolean onGenericMotionEvent(MotionEvent event){
        if(event.getAction()==MotionEvent.ACTION_SCROLL){
            float delta=event.getAxisValue(MotionEvent.AXIS_VSCROLL);
            if(delta!=0){ambient.step(delta>0?-1:1);return true;}
        }
        return super.onGenericMotionEvent(event);
    }

    @Override protected void onDestroy(){
        if(ambient!=null)ambient.stop();
        super.onDestroy();
    }
}
