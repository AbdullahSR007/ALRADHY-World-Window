package com.alradhy.worldwindow.infinity;

import android.service.dreams.DreamService;

/** Android TV screensaver: INFINITY's offline picture library with each destination's local time. */
public final class InfinityDreamService extends DreamService {
    private AmbientView ambient;

    @Override public void onAttachedToWindow(){
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);
        ambient=new AmbientView(this,null,0);
        setContentView(ambient);
    }

    @Override public void onDreamingStarted(){
        super.onDreamingStarted();
        if(ambient!=null)ambient.start();
    }

    @Override public void onDreamingStopped(){
        if(ambient!=null)ambient.stop();
        super.onDreamingStopped();
    }
}
