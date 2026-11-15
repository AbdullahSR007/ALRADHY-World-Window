package com.alradhy.worldwindow.infinity;

import android.content.Context;
import android.graphics.*;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
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
final class AmbientView extends View {
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
    private final Matrix matrix=new Matrix();
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
    private Bitmap current,previous;
    private Slide shown,leaving;
    private int shownPosition,leavingPosition;
    private long shownAt,fadeStart,pausedElapsed,hintUntil;

    /** @param sceneId one destination's pack in order, or null for the whole library shuffled. */
    AmbientView(Context context,String sceneId,int startIndex){
        super(context);
        WorldStore settings=new WorldStore(context);
        SLIDE_MS=1000L*Math.max(8,Math.min(60,settings.prefs.getInt("seconds",12)));
        fitWhole=settings.prefs.getBoolean("fit",false);
        this.sceneId=sceneId;this.startIndex=Math.max(0,startIndex);
        setFocusable(true);setFocusableInTouchMode(true);
        setLayerType(View.LAYER_TYPE_HARDWARE,null);
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
                invalidate();
            });
        });
        invalidate();
    }

    void stop(){
        main.removeCallbacksAndMessages(null);
        generation++;
        if(loader!=null){loader.shutdownNow();loader=null;}
        current=null;previous=null;
    }

    int count(){return slides.size();}

    void step(int direction){
        if(slides.size()<2)return;
        hintUntil=SystemClock.uptimeMillis()+HINT_MS;
        show(Math.floorMod(position+direction,slides.size()));
    }

    void togglePause(){
        long now=SystemClock.uptimeMillis();
        if(paused){shownAt=now-pausedElapsed;paused=false;}
        else {pausedElapsed=Math.min(now-shownAt,SLIDE_MS);paused=true;}
        hintUntil=now+HINT_MS;
        invalidate();
    }

    private void show(int index){
        if(loader==null || slides.isEmpty())return;
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
                    invalidate();
                    return;
                }
                failures=0;
                previous=current;leaving=shown;leavingPosition=shownPosition;
                current=decoded;shown=slide;shownPosition=index;
                long now=SystemClock.uptimeMillis();
                fadeStart=now;shownAt=now;pausedElapsed=0;
                invalidate();
            });
        });
    }

    @Override protected void onDetachedFromWindow(){stop();super.onDetachedFromWindow();}

    @Override protected void onDraw(Canvas c){
        super.onDraw(c);
        long now=SystemClock.uptimeMillis();
        c.drawColor(0xff03090e);
        float fade=current==null?0:Math.min(1f,(now-fadeStart)/(float)FADE_MS);
        float elapsed=paused?pausedElapsed:Math.min(now-shownAt,SLIDE_MS+FADE_MS);
        if(previous!=null && fade<1f)drawKenBurns(c,previous,1f,255,leavingPosition);
        else previous=null;
        if(current!=null)drawKenBurns(c,current,elapsed/(float)(SLIDE_MS+FADE_MS),(int)(255*fade),shownPosition);

        c.save();
        c.scale(getWidth()/1920f,getHeight()/1080f);
        drawOverlay(c,now);
        c.restore();

        if(!paused && !loading && current!=null && slides.size()>1 && now-shownAt>=SLIDE_MS)
            show(Math.floorMod(position+1,slides.size()));
        // ~30 fps keeps the motion smooth without pinning a 2 GB TV box at 60 fps.
        if(isAttachedToWindow())postInvalidateDelayed(33);
    }

    private void drawKenBurns(Canvas c,Bitmap b,float progress,int alpha,int seed){
        if(b==null || b.isRecycled())return;
        float w=getWidth(),h=getHeight();
        float cover=Math.max(w/b.getWidth(),h/b.getHeight());
        float zoom=1.04f+0.09f*Math.max(0,Math.min(1,progress));
        float scale=fitWhole?Math.min(w/b.getWidth(),h/b.getHeight()):cover*zoom;
        float dw=b.getWidth()*scale,dh=b.getHeight()*scale;
        float travelX=fitWhole?0:(dw-w)/2f,travelY=fitWhole?0:(dh-h)/2f;
        float direction=(seed%2==0)?1:-1;
        float pan=(progress-0.5f)*direction;
        float x=(w-dw)/2f+travelX*pan*0.8f;
        float y=(h-dh)/2f+travelY*((seed%3)-1)*pan*0.6f;
        matrix.reset();matrix.postScale(scale,scale);matrix.postTranslate(x,y);
        paint.setShader(null);paint.setAlpha(alpha);
        c.drawBitmap(b,matrix,paint);
        paint.setAlpha(255);
    }

    private void drawOverlay(Canvas c,long now){
        paint.setShader(new LinearGradient(0,640,0,1080,0x00000000,0xdd03090e,Shader.TileMode.CLAMP));
        c.drawRect(0,640,1920,1080,paint);
        paint.setShader(new LinearGradient(0,0,0,200,0xaa03090e,0x00000000,Shader.TileMode.CLAMP));
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
        text(c,(position+1)+" / "+slides.size(),1840,1030,21,PALE,medium,Paint.Align.RIGHT);

        // A thin progress line shows when the next picture arrives.
        float elapsed=paused?pausedElapsed:Math.min(now-shownAt,SLIDE_MS);
        paint.setColor(0x33ffffff);c.drawRect(80,1052,1840,1055,paint);
        paint.setColor(GOLD);c.drawRect(80,1052,80+1760f*elapsed/SLIDE_MS,1055,paint);

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
        paint.setShadowLayer(6,0,2,0x99000000);
        c.drawText(value,x,y,paint);
        paint.clearShadowLayer();
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private String fit(String value,int size,int width,Typeface font){
        if(value==null)return "";
        paint.setTypeface(font);paint.setTextSize(size);
        if(paint.measureText(value)<=width)return value;
        while(value.length()>4 && paint.measureText(value+"…")>width)value=value.substring(0,value.length()-1);
        return value+"…";
    }

    /** Runs off the main thread: reads picture indexes and offline scene photos. */
    static List<Slide> collect(Context context,String sceneId){
        List<Slide> result=new ArrayList<>();
        LibraryStore library=LibraryStore.shared(context);
        WorldStore world=new WorldStore(context);
        int mix=sceneId==null?world.prefs.getInt("mix",0):0;
        for(Scene scene:library.scenes){
            if(sceneId!=null && !sceneId.equals(scene.id))continue;
            String sun=world.daylight(scene);
            if(mix==2&&!sun.startsWith("Daylight"))continue;
            if(mix==3&&!sun.startsWith("Night"))continue;
            GalleryStore gallery=new GalleryStore(context,scene);
            int photoIndex=0;
            for(GalleryStore.Photo photo:gallery.saved()){
                int originalIndex=photoIndex++;
                if(mix==1&&!world.favourite(scene)&&!world.photoFavourite(scene,photo.file))continue;
                String credit=photo.credit.isEmpty()?photo.provider:photo.credit+" • "+photo.provider;
                String caption=credit;
                Slide slide=new Slide(gallery.file(photo),scene.title,scene.arabic,world.zone(scene),caption+" • "+photo.license);
                slide.sceneId=scene.id;slide.photoIndex=originalIndex;
                slide.fact=world.description(scene).replaceAll("\\s+"," ");slide.sun=world.daylightArabic(scene);result.add(slide);
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
