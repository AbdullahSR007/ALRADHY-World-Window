package com.alradhy.worldwindow.infinity;

import android.content.Context;
import android.graphics.*;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.animation.LinearInterpolator;
import java.io.File;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Offline ambient slideshow: saved picture packs and offline destination photos,
 * cross-faded with slow Ken Burns motion and the destination's local time.
 * Shared by the in-app Ambient Mode and the Android TV screensaver.
 */
final class AmbientView extends FrameLayout {
    private static final long FADE_MS=1_800, HINT_MS=5_000;
    private final long SLIDE_MS;
    private final boolean fitWhole;
    private static final int GOLD=Color.rgb(223,181,111), CREAM=Color.rgb(245,244,232), PALE=Color.rgb(178,201,205);

    static final class Slide {
        final File file;
        final String place,arabic,zone,caption;
        String fact="",sun="",sceneId="";
        int photoIndex=-1;
        Slide(File file,String place,String arabic,String zone,String caption){
            this.file=file;this.place=place;this.arabic=arabic;this.zone=zone;this.caption=caption;
        }
    }

    private final Handler main=new Handler(Looper.getMainLooper());
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final TextPaint textPaint=new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final ImageView[] images=new ImageView[2];
    private final View overlay,progress;
    private final Paint overlayPaint=new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect overlayBounds=new Rect();
    private Bitmap overlayBitmap;
    private final Shader topShade=new LinearGradient(0,0,0,200,0xaa03090e,0x00000000,Shader.TileMode.CLAMP);
    private final Shader bottomShade=new LinearGradient(0,640,0,1080,0x00000000,0xdd03090e,Shader.TileMode.CLAMP);
    private AnimatorSet motion;
    private int topImage=1;
    private final Typeface light=Typeface.create("sans-serif-light",Typeface.NORMAL);
    private final Typeface medium=Typeface.create("sans-serif-medium",Typeface.NORMAL);
    private final DateTimeFormatter clockFormat=DateTimeFormatter.ofPattern("HH:mm",Locale.US);
    private final String sceneId;
    private final int startIndex;
    String anchorScene;
    private ExecutorService loader;
    private List<Slide> slides=Collections.emptyList();
    private boolean ready,loading,paused;
    private int position,generation,failures;
    private Slide shown;
    private int shownPosition;
    private long shownAt,pausedElapsed,hintUntil;
    private final Runnable nextSlide=this::advance;
    private final Runnable expireHint=this::redrawOverlay;
    private final Runnable clockTick=this::updateClock;

    private void advance(){
        if(!paused&&!loading&&slides.size()>1)show(Math.floorMod(position+1,slides.size()));
    }

    private void updateClock(){
        redrawOverlay();
        main.removeCallbacks(clockTick);
        main.postDelayed(clockTick,60_000-System.currentTimeMillis()%60_000);
    }

    /** @param sceneId one destination's pack in order, or null for the whole library shuffled. */
    AmbientView(Context context,String sceneId,int startIndex){
        super(context);
        WorldStore settings=new WorldStore(context);
        SLIDE_MS=1000L*Math.max(8,Math.min(60,settings.prefs.getInt("seconds",12)));
        fitWhole=settings.prefs.getBoolean("fit",false);
        this.sceneId=sceneId;this.startIndex=Math.max(0,startIndex);
        setFocusable(true);setFocusableInTouchMode(true);
        setBackgroundColor(0xff03090e);
        for(int i=0;i<images.length;i++){
            ImageView image=new ImageView(context);
            image.setScaleType(fitWhole?ImageView.ScaleType.FIT_CENTER:ImageView.ScaleType.CENTER_CROP);
            images[i]=image;
            addView(image,new LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.MATCH_PARENT));
        }
        overlay=new View(context){
            @Override protected void onDraw(Canvas canvas){
                if(overlayBitmap!=null){
                    overlayBounds.set(0,0,getWidth(),getHeight());
                    canvas.drawBitmap(overlayBitmap,null,overlayBounds,overlayPaint);
                }
            }
        };
        addView(overlay,new LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.MATCH_PARENT));
        progress=new View(context);
        progress.setBackgroundColor(GOLD);
        progress.setVisibility(INVISIBLE);
        addView(progress,new LayoutParams(1,1));
        setKeepScreenOn(true);
    }

    void start(){
        if(loader!=null)return;
        loader=Executors.newSingleThreadExecutor();
        hintUntil=SystemClock.uptimeMillis()+HINT_MS;
        Context context=getContext();
        loader.execute(()->{
            List<Slide> found=collect(context,sceneId);
            main.post(()->{
                if(loader==null)return;
                slides=found;ready=true;
                int first=Math.min(startIndex,slides.size()-1);
                if(anchorScene!=null)for(int i=0;i<slides.size();i++){
                    Slide slide=slides.get(i);
                    if(anchorScene.equals(slide.sceneId)&&slide.photoIndex==startIndex){first=i;break;}
                }
                if(!slides.isEmpty())show(first);
                redrawOverlay();
            });
        });
        updateClock();
    }

    void stop(){
        main.removeCallbacksAndMessages(null);
        generation++;
        if(loader!=null){loader.shutdownNow();loader=null;}
        if(motion!=null){motion.cancel();motion=null;}
        for(ImageView image:images){
            image.animate().cancel();
            image.setImageDrawable(null);
        }
        shown=null;
        ready=false;
        loading=false;
        paused=false;
        slides=Collections.emptyList();
        progress.setVisibility(INVISIBLE);
        overlayBitmap=null;
        overlay.invalidate();
    }

    int count(){return slides.size();}

    void step(int direction){
        if(slides.size()<2)return;
        hintUntil=SystemClock.uptimeMillis()+HINT_MS;
        show(Math.floorMod(position+direction,slides.size()));
    }

    void togglePause(){
        long now=SystemClock.uptimeMillis();
        if(paused){
            shownAt=now-pausedElapsed;
            paused=false;
            if(motion!=null)motion.resume();
            if(shown!=null)scheduleAdvance(SLIDE_MS-pausedElapsed);
        }else{
            pausedElapsed=Math.min(Math.max(0,now-shownAt),SLIDE_MS);
            paused=true;
            if(motion!=null)motion.pause();
            main.removeCallbacks(nextSlide);
        }
        hintUntil=now+HINT_MS;
        redrawOverlay();
    }

    private void show(int index){
        if(loader==null || slides.isEmpty())return;
        main.removeCallbacks(nextSlide);
        position=index;loading=true;
        final int run=++generation;
        final Slide slide=slides.get(index);
        final int edge=Math.max(960,Math.max(getWidth(),getHeight())/2);
        loader.execute(()->{
            Bitmap bitmap=null;
            try{bitmap=GalleryStore.bitmap(slide.file,edge);}catch(Throwable ignored){/* unreadable file: skip it */}
            final Bitmap decoded=bitmap;
            main.post(()->{
                if(run!=generation || loader==null)return;
                loading=false;
                if(decoded==null){
                    // Skip unreadable files, but never spin forever on a library of broken files.
                    if(++failures<Math.min(slides.size(),12) && slides.size()>1)
                        show(Math.floorMod(position+1,slides.size()));
                    redrawOverlay();
                    return;
                }
                failures=0;
                display(decoded,slide,index);
            });
        });
    }

    private void scheduleAdvance(long delay){
        main.removeCallbacks(nextSlide);
        if(!paused&&slides.size()>1)main.postDelayed(nextSlide,Math.max(0,delay));
    }

    private void display(Bitmap bitmap,Slide slide,int index){
        if(motion!=null)motion.cancel();
        ImageView leaving=images[topImage];
        leaving.animate().cancel();
        leaving.setAlpha(1f);
        topImage=1-topImage;
        ImageView incoming=images[topImage];
        incoming.animate().cancel();
        incoming.setImageBitmap(bitmap);
        incoming.setAlpha(0f);
        incoming.bringToFront();
        overlay.bringToFront();
        progress.bringToFront();
        incoming.animate().alpha(1f).setDuration(FADE_MS).withEndAction(()->{
            if(images[topImage]==incoming)leaving.setImageDrawable(null);
        }).start();

        shown=slide;
        shownPosition=index;
        shownAt=SystemClock.uptimeMillis();
        pausedElapsed=0;

        float start=fitWhole?1f:1.04f,end=fitWhole?1f:1.13f;
        float direction=index%2==0?1f:-1f;
        incoming.setScaleX(start);
        incoming.setScaleY(start);
        progress.setScaleX(0f);
        progress.setVisibility(VISIBLE);
        motion=new AnimatorSet();
        ObjectAnimator panX=ObjectAnimator.ofFloat(incoming,View.TRANSLATION_X,
            fitWhole?0:-direction*getWidth()*.004f,fitWhole?0:direction*getWidth()*.013f);
        ObjectAnimator panY=ObjectAnimator.ofFloat(incoming,View.TRANSLATION_Y,
            0,fitWhole?0:(index%3-1)*getHeight()*.012f);
        ObjectAnimator progressFill=ObjectAnimator.ofFloat(progress,View.SCALE_X,0f,1f);
        progressFill.setDuration(SLIDE_MS);
        ObjectAnimator zoomX=ObjectAnimator.ofFloat(incoming,View.SCALE_X,start,end);
        ObjectAnimator zoomY=ObjectAnimator.ofFloat(incoming,View.SCALE_Y,start,end);
        for(ObjectAnimator transform:Arrays.asList(panX,panY,zoomX,zoomY))transform.setDuration(SLIDE_MS+FADE_MS);
        motion.playTogether(panX,panY,zoomX,zoomY,progressFill);
        motion.setInterpolator(new LinearInterpolator());
        motion.start();
        if(paused)motion.pause();
        scheduleAdvance(SLIDE_MS);
        redrawOverlay();
    }

    @Override protected void onDetachedFromWindow(){stop();super.onDetachedFromWindow();}

    @Override protected void onSizeChanged(int width,int height,int oldWidth,int oldHeight){
        super.onSizeChanged(width,height,oldWidth,oldHeight);
        int barWidth=Math.max(1,Math.round(width*1760f/1920));
        LayoutParams bar=new LayoutParams(barWidth,Math.max(1,Math.round(height*3f/1080)));
        bar.leftMargin=Math.round(width*80f/1920);
        bar.topMargin=Math.round(height*1052f/1080);
        progress.setLayoutParams(bar);
        progress.setPivotX(0);
        overlayBitmap=null;
        redrawOverlay();
    }

    private void redrawOverlay(){
        int width=getWidth(),height=getHeight();
        if(width==0||height==0||loader==null)return;
        Bitmap rendered=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(rendered);
        canvas.scale(width/1920f,height/1080f);
        long now=SystemClock.uptimeMillis();
        drawOverlay(canvas,now);
        overlayBitmap=rendered;
        overlay.invalidate();
        main.removeCallbacks(expireHint);
        if(!paused&&now<hintUntil)main.postDelayed(expireHint,hintUntil-now);
    }

    private void drawOverlay(Canvas c,long now){
        paint.setShader(bottomShade);
        c.drawRect(0,640,1920,1080,paint);
        paint.setShader(topShade);
        c.drawRect(0,0,1920,200,paint);
        paint.setShader(null);

        text(c,"ALRADHY  •  INFINITY  ∞  AMBIENT",80,92,22,GOLD,medium,Paint.Align.LEFT);
        if(!ready){
            text(c,"Gathering your offline pictures…",960,560,34,PALE,light,Paint.Align.CENTER);
            return;
        }
        if(slides.isEmpty()){
            text(c,"No saved pictures match this selection",960,520,58,CREAM,light,Paint.Align.CENTER);
            text(c,"Open a destination → PICTURES → SAVE THIS PACK, or use DOWNLOAD ALL PICTURES.",
                960,590,26,PALE,medium,Paint.Align.CENTER);
            text(c,"Check your Ambient mix or refresh sun times in OPTIONS.",960,632,26,PALE,medium,Paint.Align.CENTER);
            text(c,ZonedDateTime.now().format(clockFormat),1840,110,64,CREAM,light,Paint.Align.RIGHT);
            return;
        }
        Slide s=shown;
        if(s==null)return;
        String zone=s.zone==null||s.zone.isEmpty()?"Etc/UTC":s.zone;
        String clock;
        try{clock=ZonedDateTime.now(ZoneId.of(zone)).format(clockFormat);}catch(Exception e){clock="--:--";}
        text(c,clock,1840,118,72,CREAM,light,Paint.Align.RIGHT);
        text(c,"LOCAL TIME  •  "+zone.replace('_',' '),1840,156,19,PALE,medium,Paint.Align.RIGHT);

        text(c,fit(s.sun,23,1600,medium),80,760,23,GOLD,medium,Paint.Align.LEFT);
        text(c,fit(s.fact,23,1700,medium),80,810,23,CREAM,medium,Paint.Align.LEFT);
        if(!s.fact.isEmpty())text(c,"وصف عربي • تفاصيل المصدر في خيارات المكان",80,845,17,PALE,medium,Paint.Align.LEFT);
        text(c,fit(s.place,78,1300,light),80,930,78,CREAM,light,Paint.Align.LEFT);
        if(!s.arabic.isEmpty())text(c,s.arabic,82,985,36,0xffe9c891,medium,Paint.Align.LEFT);
        text(c,fit(s.caption,19,1250,medium),84,1030,19,PALE,medium,Paint.Align.LEFT);
        text(c,(shownPosition+1)+" / "+slides.size(),1840,1030,21,PALE,medium,Paint.Align.RIGHT);

        // A thin progress line shows when the next picture arrives.
        paint.setColor(0x33ffffff);c.drawRect(80,1052,1840,1055,paint);

        if(paused || now<hintUntil){
            String hint=paused?"Ⅱ  PAUSED   •   OK RESUME   •   ◀ ▶ BROWSE   •   BACK EXIT"
                :"◀ ▶ BROWSE   •   OK PAUSE   •   BACK EXIT";
            paint.setTypeface(medium);paint.setTextSize(21);
            float width=paint.measureText(hint)+64;
            paint.setColor(0xaa071820);
            c.drawRoundRect(960-width/2,206,960+width/2,258,26,26,paint);
            text(c,hint,960,240,21,CREAM,medium,Paint.Align.CENTER);
        }
    }

    private void text(Canvas c,String value,float x,float y,float size,int color,Typeface font,Paint.Align align){
        paint.setShader(null);paint.setColor(color);paint.setTypeface(font);paint.setTextSize(size);
        paint.setTextAlign(align);
        c.drawText(value,x,y,paint);
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private String fit(String value,int size,int width,Typeface font){
        if(value==null)return "";
        textPaint.setTypeface(font);
        textPaint.setTextSize(size);
        return TextUtils.ellipsize(value,textPaint,width,TextUtils.TruncateAt.END).toString();
    }

    /** Runs off the main thread: reads picture indexes and offline scene photos. */
    static List<Slide> collect(Context context,String sceneId){
        List<Slide> result=new ArrayList<>();
        LibraryStore library=LibraryStore.shared(context);
        WorldStore world=new WorldStore(context);
        int mix=sceneId==null?world.prefs.getInt("mix",0):0;
        for(Scene scene:library.scenes){
            if(sceneId!=null && !sceneId.equals(scene.id))continue;
            org.json.JSONObject info=world.info(scene);
            String sun=world.daylight(scene,info);
            if(mix==2&&!sun.startsWith("Daylight"))continue;
            if(mix==3&&!sun.startsWith("Night"))continue;
            GalleryStore gallery=new GalleryStore(context,scene);
            String zone=world.zone(scene,info);
            String fact=world.description(scene,info).replaceAll("\\s+"," ");
            String sunArabic=world.daylightArabic(scene,info);
            int photoIndex=0;
            for(GalleryStore.Photo photo:gallery.saved()){
                int originalIndex=photoIndex++;
                if(mix==1&&!world.favourite(scene)&&!world.photoFavourite(scene,photo.file))continue;
                String credit=photo.credit.isEmpty()?photo.provider:photo.credit+" • "+photo.provider;
                String caption=credit;
                Slide slide=new Slide(gallery.file(photo),scene.title,scene.arabic,zone,caption+" • "+photo.license);
                slide.sceneId=scene.id;slide.photoIndex=originalIndex;
                slide.fact=fact;slide.sun=sunArabic;result.add(slide);
            }
            if(sceneId==null && (mix!=1||world.favourite(scene)) && scene.isOffline() && !scene.isVideo()){
                String credit=scene.hasOfflinePicture()?scene.offlineCredit:scene.credit;
                result.add(new Slide(new File(scene.localMedia),scene.title,scene.arabic,scene.zone,
                    credit.isEmpty()?WorldStore.categoryName(scene.category):WorldStore.categoryName(scene.category)+"   •   "+credit));
            }
        }
        if(sceneId==null && world.prefs.getBoolean("shuffle",true))Collections.shuffle(result);
        return result;
    }
}
