package com.alradhy.worldwindow.infinity;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.LruCache;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.graphics.*;
import android.view.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * TV-first, 1920x1080 reference canvas. No WebView, no giant scrolling page.
 * Remote DPAD, select/back, mouse hover/click, wheel and swipe all work natively.
 */
public final class CinematicView extends View {
    public interface Actions {
        void play(Scene scene);
        void tools(Scene scene, GalleryStore.Photo photo);
        void addPlace();
        void download(Scene scene);
        void downloadAllOrPause();
        void downloadPictures(Scene scene);
        void removePictures(Scene scene);
        void ambient(Scene scene,int start);
        void categoryChanged(List<Scene> visible);
        void sceneChanged(Scene scene);
    }
    private static final int BG=Color.rgb(5,18,26);
    private static final int GOLD=Color.rgb(223,181,111);
    private static final int CREAM=Color.rgb(245,244,232);
    private static final int PALE=Color.rgb(178,201,205);
    private static final int PANEL=Color.rgb(13,36,45);
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final LibraryStore library;
    private final PosterLoader posters;
    private final Actions actions;
    private final ArrayList<Scene> visible=new ArrayList<>();
    private int categoryIndex=0,index=0,focus=1,buttonIndex=0;
    private int photoIndex;
    private GalleryStore pictureStore;
    private List<GalleryStore.Photo> photos=Collections.emptyList();
    private int hover=-1;
    /** Header chips: the five categories, then AMBIENT. */
    private int headerIndex=0;
    private static final int AMBIENT_CHIP=6, ADD_CHIP=7, TOOLS_CHIP=8, CHIP_X=700, CHIP_STEP=130, CHIP_W=122;
    private volatile GalleryStore.Summary pictures=GalleryStore.Summary.EMPTY;
    private float downX,downY;
    private final Typeface heading=Typeface.create("sans-serif-light",Typeface.NORMAL);
    private final Typeface medium=Typeface.create("sans-serif-medium",Typeface.NORMAL);
    private final DateTimeFormatter timeFormat=DateTimeFormatter.ofPattern("HH:mm",Locale.US);

    private static final LinearGradient HERO_EMPTY=gradient(220,150,1300,730,0xff122d39,0xff255c6a,0xff0a2731);
    private static final LinearGradient HERO_BOTTOM=gradient(222,400,222,730,0x00101b24,0xe908171f);
    private static final LinearGradient HERO_LEFT=gradient(222,157,700,157,0x7704121a,0x00000000);
    private static final LinearGradient CARD_EMPTY=gradient(0,0,308,205,0xff255264,0xff102e3d);
    private static final LinearGradient CARD_BOTTOM=gradient(0,80,0,205,0x00101920,0xee081821);
    private static LinearGradient gradient(float x,float y,float endX,float endY,int...colors){
        return new LinearGradient(x,y,endX,endY,colors,null,Shader.TileMode.CLAMP);
    }
    private final Matrix imageMatrix=new Matrix();
    private final LruCache<Bitmap,BitmapShader> shaders=new LruCache<>(8);
    private final LruCache<String,String> fitted=new LruCache<>(256);
    private final TextPaint textPaint=new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private Bitmap background;
    private final WorldStore world;
    private final ExecutorService models=Executors.newSingleThreadExecutor();
    private final ExecutorService packs=Executors.newSingleThreadExecutor();
    private int modelRequest,pictureRequest;
    private boolean closed,reloadPicturesPending,pictureOpening;
    private volatile boolean active;
    private boolean awaitingSelection;
    private String pictureSceneId;
    private String footer="",videoProgress="",pictureProgress="";
    private boolean downloading,savingPictures,unfinished,videoInterrupted,pictureInterrupted;
    private float videoFraction,pictureFraction;
    private final Map<String,MediaStatus> mediaStates=new LinkedHashMap<>();
    private SceneDetails details;
    private String positionLabel="";
    private final Runnable posterReady=()->post(()->{if(active)invalidate();});
    private final Runnable minuteTick=new Runnable(){
        @Override public void run(){
            refreshScene();
            scheduleMinute();
        }
    };

    private static final class MediaStatus {
        final boolean offline,photo;
        final String label;
        MediaStatus(Scene scene){
            offline=scene.isOffline();
            photo=offline&&scene.localMedia.endsWith("-offline-photo.img");
            label=scene.downloading?"DOWNLOADING":offline?(photo?"OFFLINE PICTURE":"OFFLINE READY"):
                scene.resolving?"DISCOVERING":scene.hasSource()?"ONLINE READY":
                !scene.error.isEmpty()?"RETRY AVAILABLE":"AWAITING DISCOVERY";
        }
    }

    private static final class SceneDetails {
        final Scene scene;
        final String description,zone,sun,clock;
        final boolean favourite;
        SceneDetails(WorldStore world,Scene scene,DateTimeFormatter format){
            this.scene=scene;
            org.json.JSONObject info=world.info(scene);
            description=world.description(scene,info);
            zone=world.zone(scene,info);
            sun=world.daylightArabic(scene,info);
            favourite=world.favourite(scene);
            String value;
            try{value=ZonedDateTime.now(ZoneId.of(zone)).format(format);}
            catch(java.time.DateTimeException invalidZone){value="--:--";}
            clock=value;
        }
    }

    void resumeUpdates(){
        active=true;
        refreshScene();
        refreshPictures();
        scheduleMinute();
    }
    void pauseUpdates(){
        active=false;
        removeCallbacks(minuteTick);
    }
    private void scheduleMinute(){
        removeCallbacks(minuteTick);
        if(active)postDelayed(minuteTick,60_000-System.currentTimeMillis()%60_000);
    }

    void refreshScene(){
        if(!active||closed)return;
        removeCallbacks(updateScene);
        post(updateScene);
    }

    private final Runnable updateScene=this::updateSceneSnapshot;
    private void updateSceneSnapshot(){
        if(!active||closed)return;
        final int request=++modelRequest;
        final List<Scene> page=firstToResolve();
        final Scene selected=selected();
        models.execute(()->{
            Map<String,MediaStatus> statuses=new HashMap<>();
            for(Scene item:page)statuses.put(item.id,new MediaStatus(item));
            SceneDetails snapshot=selected==null?null:new SceneDetails(world,selected,timeFormat);
            post(()->{
                if(closed||!active||request!=modelRequest||selected!=selected())return;
                if(mediaStates.size()>200)mediaStates.clear();
                mediaStates.putAll(statuses);
                details=snapshot;
                awaitingSelection=false;
                invalidate();
            });
        });
    }

    void setProgress(SharedPreferences video,SharedPreferences picture){
        downloading=OfflineDownloadService.isRunning();
        savingPictures=OfflineDownloadService.isSavingPictures();
        videoProgress=video.getString("status","");
        pictureProgress=WorldStore.pictureStatus(picture.getString("status",""));
        videoInterrupted=video.getBoolean("active",false);
        pictureInterrupted=picture.getBoolean("active",false);
        unfinished=videoInterrupted||video.getInt("errors",0)>0||
            videoProgress.startsWith("Paused")||videoProgress.startsWith("Storage low");
        videoFraction=fraction(video);
        pictureFraction=fraction(picture);
        invalidate();
    }

    private float fraction(SharedPreferences prefs){
        int total=prefs.getInt("total",0);
        return total>0?(float)prefs.getInt("done",0)/total:0;
    }

    public CinematicView(Context c,LibraryStore store,Actions listener){
        super(c);library=store;actions=listener;posters=new PosterLoader(store);world=new WorldStore(c);
        visible.addAll(store.scenes);
        setFocusable(true);setFocusableInTouchMode(true);
    }
    public Scene selected(){return visible.isEmpty()?null:visible.get(Math.max(0,Math.min(index,visible.size()-1)));}
    String selectedCategory(){return SceneCatalog.CATEGORIES[categoryIndex];}
    void setSummary(GalleryStore.Summary summary,String status){
        pictures=summary;
        footer=status;
        invalidate();
    }
    private void selectionUpdated(){
        positionLabel=String.format(Locale.US,"%02d  /  %02d",index+1,visible.size());
        awaitingSelection=active;
        refreshScene();
        if(!active)invalidate();
    }
    private int chipAt(float x){return Math.max(0,Math.min(TOOLS_CHIP,(int)((x-CHIP_X)/CHIP_STEP)));}
    void restoreSelection(String category,String sceneId){
        int savedCategory=Arrays.asList(SceneCatalog.CATEGORIES).indexOf(category);
        if(savedCategory<0)return;
        showCategory(savedCategory);
        for(int i=0;i<visible.size();i++){
            if(visible.get(i).id.equals(sceneId)){
                index=i;
                break;
            }
        }
        selectionUpdated();
    }
    public List<Scene> firstToResolve(){
        ArrayList<Scene> result=new ArrayList<>();
        int start=(index/5)*5;
        for(int i=start;i<Math.min(start+5,visible.size());i++)result.add(visible.get(i));
        return result;
    }
    public void selectCategory(int i){
        showCategory(Math.max(0,Math.min(SceneCatalog.CATEGORIES.length-1,i)));
        selectionUpdated();actions.categoryChanged(firstToResolve());
        Scene s=selected();if(s!=null)actions.sceneChanged(s);
    }
    private void showCategory(int category){
        closePictures();
        categoryIndex=category;
        headerIndex=category;
        visible.clear();visible.addAll(library.category(SceneCatalog.CATEGORIES[categoryIndex]));
        index=0;
    }
    private void move(int d){
        if(pictureStore!=null){
            if(!photos.isEmpty())photoIndex=Math.floorMod(photoIndex+d,photos.size());
            invalidate();
            return;
        }
        if(visible.isEmpty())return;
        index=(index+d+visible.size())%visible.size();
        selectionUpdated();actions.categoryChanged(firstToResolve());
        Scene s=selected();if(s!=null)actions.sceneChanged(s);
    }
    private void showPictures(Scene scene){
        pictureStore=new GalleryStore(getContext(),scene);
        pictureSceneId=scene.id;
        photoIndex=0;
        pictureOpening=true;
        refreshPictures();
        invalidate();
    }
    boolean closePictures(){
        if(pictureStore==null)return false;
        pictureStore=null;
        pictureSceneId=null;
        pictureOpening=false;
        pictureRequest++;
        photos=Collections.emptyList();
        focus=1;
        invalidate();
        return true;
    }
    void packChanged(String id){
        if(Objects.equals(id,pictureSceneId))refreshPictures();
    }
    void refreshPictures(){
        if(pictureStore==null||closed)return;
        if(reloadPicturesPending)return;
        reloadPicturesPending=true;
        post(reloadPictures);
    }
    private final Runnable reloadPictures=()->{
        reloadPicturesPending=false;
        GalleryStore store=pictureStore;
        if(store==null||closed)return;
        int request=++pictureRequest;
        packs.execute(()->{
            List<GalleryStore.Photo> saved=store.saved();
            post(()->{
                if(closed||pictureStore!=store||pictureRequest!=request)return;
                photos=saved;
                photoIndex=Math.max(0,Math.min(photoIndex,photos.size()-1));
                if(pictureOpening){
                    pictureOpening=false;
                    focus=photos.isEmpty()?2:1;
                    buttonIndex=photos.isEmpty()?2:1;
                }
                invalidate();
            });
        });
    };
    @Override protected void onSizeChanged(int width,int height,int oldWidth,int oldHeight){
        super.onSizeChanged(width,height,oldWidth,oldHeight);
        if(width==0||height==0)return;
        background=Bitmap.createBitmap(Math.min(width,960),Math.min(height,540),Bitmap.Config.RGB_565);
        Canvas canvas=new Canvas(background);
        canvas.scale(background.getWidth()/1920f,background.getHeight()/1080f);
        paintBackground(canvas);
    }
    private final Rect backgroundBounds=new Rect(0,0,1920,1080);
    @Override protected void onDraw(Canvas raw){
        super.onDraw(raw);
        float sx=getWidth()/1920f,sy=getHeight()/1080f;
        raw.save();raw.scale(sx,sy);
        if(background!=null){p.setColor(Color.WHITE);p.setShader(null);raw.drawBitmap(background,null,backgroundBounds,p);}
        drawRail(raw);
        drawHeader(raw);
        Scene s=selected();
        if(s!=null){
            if(pictureStore!=null){drawPictureHero(raw,s);drawPictureControls(raw,s);}
            else {drawHero(raw,s);drawDetails(raw,s);}
        }
        if(s==null)label(raw,"Use + PLACE or favourite a destination in OPTIONS to build My World.",240,450,28,CREAM,medium,false);
        if(pictureStore!=null)drawPictureStrip(raw);
        else drawStrip(raw);
        drawFooter(raw);
        raw.restore();
    }
    private void paintBackground(Canvas c){
        p.setShader(new LinearGradient(0,0,1900,1080,new int[]{0xff06111a,0xff0a2733,0xff09121b},null,Shader.TileMode.CLAMP));
        c.drawRect(0,0,1920,1080,p);p.setShader(null);
        p.setColor(0x2230afc3);
        for(int i=0;i<17;i++)c.drawCircle(1800-i*43,105+i*51,120+i*13,p);
        Random random=new Random(75);
        for(int i=0;i<95;i++){
            p.setColor((i%4==0)?0x44ffe8c5:0x228abec5);
            c.drawCircle(130+random.nextInt(1760),random.nextInt(1080),1+random.nextInt(3),p);
        }
    }
    private void drawRail(Canvas c){
        rect(c,0,0,151,1080,0xee06131c,0);
        rect(c,149,0,2,1080,0x44cba56b,0);
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(3.2f);p.setColor(GOLD);
        c.drawCircle(75,83,41,p);
        Path inf=new Path();inf.moveTo(46,83);
        inf.cubicTo(54,51,67,63,75,83);inf.cubicTo(84,105,101,104,104,83);
        inf.cubicTo(99,61,86,64,75,83);inf.cubicTo(59,106,48,101,46,83);
        c.drawPath(inf,p);p.setStyle(Paint.Style.FILL);
        String[] marks={"⌂","◉","△","≈","✧"};
        for(int i=0;i<5;i++){
            int y=282+i*126;
            if(i==categoryIndex)rounded(c,13,y-50,124,102,17,0x333abaad);
            label(c,marks[i],75,y+14,55,i==categoryIndex?GOLD:0xff6e919a,medium,true);
            if(i==categoryIndex)rounded(c,147,y-34,5,67,2,GOLD);
        }
        label(c,"INFINITY",76,1012,17,0xff8da4a9,medium,true);
    }
    private void drawHeader(Canvas c){
        label(c,"ALRADHY",232,66,36,GOLD,medium,false);
        label(c,"WORLD WINDOW",232,114,51,CREAM,heading,false);


        for(int i=0;i<=TOOLS_CHIP;i++){
            int x=CHIP_X+i*CHIP_STEP;
            boolean ambientChip=i==AMBIENT_CHIP;
            boolean current=i<SceneCatalog.CATEGORIES.length && i==categoryIndex;
            boolean focused=focus==0 && headerIndex==i;
            rounded(c,x,57,CHIP_W,57,16,ambientChip?0x66a87b42:current?0x668db3af:0x55203843);
            if(focused)outline(c,x-3,54,CHIP_W+6,63,18,GOLD,3.5f);
            else if(current||hover==i)outline(c,x,57,CHIP_W,57,16,current?GOLD:0xff6b9da7,2);
            label(c,ambientChip?"AMBIENT":i==ADD_CHIP?"+ PLACE":i==TOOLS_CHIP?"OPTIONS":SceneCatalog.CATEGORIES[i],x+CHIP_W/2f,94,18,
                current||ambientChip?CREAM:PALE,medium,true);
        }
        rounded(c,224,132,1650,1,0,0x66789598);
    }
    private void drawHero(Canvas c,Scene s){
        rounded(c,222,157,1118,558,25,0xff0b303f);
        MediaStatus media=mediaStates.get(s.id);
        Bitmap bitmap=posters.peek(s,media!=null&&media.offline,1120,560,posterReady);
        if(bitmap!=null)image(c,bitmap,222,157,1118,558,25);
        else {
            p.setShader(HERO_EMPTY);
            c.drawRoundRect(222,157,1340,715,25,25,p);p.setShader(null);
            drawGlobe(c,840,410);
        }
        // Overlay: text stays readable regardless of photographic highlights.
        p.setShader(HERO_BOTTOM);
        c.drawRoundRect(222,157,1340,715,25,25,p);p.setShader(null);
        p.setShader(HERO_LEFT);
        c.drawRoundRect(222,157,1340,715,25,25,p);p.setShader(null);
        boolean offline=media!=null&&media.offline;
        pill(c,255,194,offline?"● OFFLINE COLLECTION":s.mime.startsWith("video/")?"▶ CINEMATIC FOOTAGE":"◈ WORLD GALLERY",
             offline?0xff408e81:0xaa162e3b);
        String title=fit(s.title,76,950);
        label(c,title,269,567,Math.min(76,title.length()>20?62:76),CREAM,heading,false);
        label(c,s.arabic,274,628,37,0xffe9c891,medium,false);
        label(c,WorldStore.categoryName(s.category),275,676,25,0xffd5e5e5,heading,false);
        outline(c,222,157,1118,558,25,0x44d2a66b,1.8f);
    }
    private void drawGlobe(Canvas c,float cx,float cy){
        p.setColor(0x1148b4ca);c.drawCircle(cx,cy,240,p);
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.7f);p.setColor(0x6676d2c9);
        c.drawCircle(cx,cy,207,p);c.drawOval(cx-98,cy-207,cx+98,cy+207,p);
        c.drawOval(cx-205,cy-90,cx+205,cy+90,p);
        c.drawLine(cx-207,cy,cx+207,cy,p);
        p.setStyle(Paint.Style.FILL);
    }
    private void drawPictureHero(Canvas c,Scene scene){
        rounded(c,222,157,1118,558,25,0xff06151e);
        if(photos.isEmpty()){
            label(c,fit(scene.title,58,950),269,433,58,CREAM,heading,false);
            label(c,"Save pictures to view them here, even offline",271,487,25,PALE,medium,false);
        }else{
            GalleryStore.Photo photo=photos.get(photoIndex);
            Bitmap bitmap=posters.peekPicture(pictureStore.file(photo),1120,560,posterReady);
            if(bitmap!=null){
                float scale=Math.min(1118f/bitmap.getWidth(),465f/bitmap.getHeight());
                float wide=bitmap.getWidth()*scale, high=bitmap.getHeight()*scale;
                image(c,bitmap,222+(1118-wide)/2,157+(465-high)/2,wide,high,4);
            }
            label(c,fit(details!=null&&details.scene==scene?details.description:"",26,1000),253,653,26,CREAM,heading,false);
            String credit=photo.credit.isEmpty()?photo.provider:photo.credit+" • "+photo.provider;
            label(c,fit(credit+" • "+photo.license,18,1035),253,686,18,PALE,medium,false);
        }
        outline(c,222,157,1118,558,25,0x44d2a66b,1.8f);
    }
    private void drawPictureControls(Canvas c,Scene scene){
        rounded(c,1360,157,516,558,26,0xdd102b35);
        outline(c,1360,157,516,558,26,0x6669979b,1.8f);
        label(c,"PICTURES",1402,215,27,GOLD,medium,false);
        label(c,fit(scene.title,42,430),1402,313,42,CREAM,heading,false);
        label(c,photos.isEmpty()?"لم تُحفظ صور بعد":photos.size()+" صورة محفوظة • جاهزة للعرض",1404,371,23,PALE,medium,false);
        boolean running=savingPictures;
        action(c,0,1404,430,"←    BACK TO DESTINATIONS",false);
        action(c,1,1404,495,"▶    SLIDESHOW • ALL PLACES",false);
        action(c,2,1404,560,photos.isEmpty()?"↓    حفظ الصور":"▧    إدارة الصور المحفوظة",false);
        action(c,3,1404,625,running?"Ⅱ    PAUSE PICTURE DOWNLOADS":"⇩    DOWNLOAD ALL PICTURES",true);
        label(c,pictures.packs+" ready packs  •  "+pictures.photos+" photos  •  "+library.human(pictures.bytes),
            1408,703,18,0xffb9ccd0,medium,false);
    }
    private void drawPictureStrip(Canvas c){
        label(c,"OFFLINE PICTURES",229,759,26,GOLD,medium,false);
        if(photos.isEmpty())return;
        label(c,"OK  FULL SCREEN     "+(photoIndex+1)+" / "+photos.size()+"     ◀  ▶",1865,757,21,PALE,medium,false,true);
        int start=photoIndex/5*5;
        for(int i=0;i<5 && start+i<photos.size();i++){
            int j=start+i;
            GalleryStore.Photo photo=photos.get(j);
            int x=228+i*328;
            rounded(c,x,789,308,205,15,0xff183744);
            Bitmap bitmap=posters.peekPicture(pictureStore.file(photo),320,213,posterReady);
            if(bitmap!=null)image(c,bitmap,x,789,308,205,15);
            if(j==photoIndex)outline(c,x-3,786,314,211,17,GOLD,focus==1?4.3f:2.5f);
        }
    }
    private void drawDetails(Canvas c,Scene s){
        rounded(c,1360,157,516,558,26,0xdd102b35);
        outline(c,1360,157,516,558,26,0x6669979b,1.8f);
        SceneDetails data=details!=null&&details.scene==s?details:null;
        MediaStatus media=mediaStates.get(s.id);
        boolean offline=media!=null&&media.offline;
        label(c,data!=null&&data.favourite?"♥  MY WORLD":"A WINDOW TO WONDER",1401,207,21,GOLD,medium,false);
        label(c,positionLabel,1815,207,22,PALE,medium,true);
        label(c,data==null?"":data.sun,1402,277,20,0xff9bb7bd,medium,false);
        label(c,fit(s.title,52,436),1402,344,52,CREAM,heading,false);
        label(c,data==null?"--:--":data.clock,1404,414,48,GOLD,medium,false);
        if(data!=null)label(c,fit("LOCAL TIME • "+data.zone.replace('_',' '),20,265),1560,410,20,PALE,medium,false);
        action(c,0,1404,430,"▶    EXPLORE NOW",false);
        int saved=pictures.count(s);
        action(c,1,1404,495,saved>0?"▧    PICTURES  •  "+saved+" SAVED":"▧    PICTURES",false);
        action(c,2,1404,560,offline?"×    REMOVE OFFLINE COPY":"↓    SAVE THIS SCENE",false);
        action(c,3,1404,625,savingPictures?"Ⅱ    PAUSE PICTURE DOWNLOADS":
            downloading?"Ⅱ    PAUSE DOWNLOADS":
            unfinished?"⇩    RESUME DOWNLOADS":"⇩    DOWNLOAD ALL",true);
        label(c,media==null?"":media.label,1408,703,18,offline?0xff91e0c4:0xffb9ccd0,medium,false);
    }
    private void action(Canvas c,int n,int x,int y,String name,boolean warm){
        int fill=warm?0xffa87b42:0xff254a58;
        rounded(c,x,y,429,57,14,fill);
        if(focus==2&&buttonIndex==n)outline(c,x-3,y-3,435,63,15,GOLD,4);
        label(c,name,x+25,y+37,23,warm?0xff0c1a1f:CREAM,medium,false);
    }
    private void drawStrip(Canvas c){
        label(c,"EXPLORE THE COLLECTION",229,759,26,GOLD,medium,false);
        label(c,"◀  DPAD / MOUSE / SWIPE  ▶",1565,757,18,PALE,medium,false);
        int start=(index/5)*5;
        for(int i=0;i<5;i++){
            int j=start+i;if(j>=visible.size())break;
            Scene s=visible.get(j);
            int x=228+i*328,y=789;
            rounded(c,x,y,308,205,15,0xff183744);
            MediaStatus media=mediaStates.get(s.id);
            boolean offline=media!=null&&media.offline;
            Bitmap bmp=posters.peek(s,offline,320,213,posterReady);
            if(bmp!=null)image(c,bmp,x,y,308,205,15);
            else{
                cardShade(c,CARD_EMPTY,x,y);
                p.setStyle(Paint.Style.STROKE);p.setColor(0x447ed4d8);p.setStrokeWidth(2);
                c.drawCircle(x+154,y+80,46,p);p.setStyle(Paint.Style.FILL);
            }
            cardShade(c,CARD_BOTTOM,x,y);
            label(c,fit(s.title,29,280),x+16,y+165,29,CREAM,medium,false);
            label(c,media!=null&&media.photo?"● PHOTO SAVED":offline?"● SAVED":s.mime.startsWith("video/")?"▶ VIDEO":"◇ "+s.category,
                x+17,y+188,15,offline?0xff8edac5:GOLD,medium,false);
            int pack=pictures.count(s);
            if(pack>0){
                String badge="▧ "+pack;
                p.setTypeface(medium);p.setTextSize(16);
                float bw=p.measureText(badge)+22;
                rounded(c,x+308-bw-10,y+10,bw,30,14,0xdd2f7a6c);
                label(c,badge,x+308-bw-10+11,y+31,16,CREAM,medium,false);
            }
            if(j==index)outline(c,x-3,y-3,314,211,17,
                focus==1?GOLD:0xffa5bebb,focus==1?4.3f:2.5f);
        }
    }
    private void cardShade(Canvas c,Shader shade,int x,int y){
        c.save();
        c.translate(x,y);
        p.setShader(shade);
        c.drawRoundRect(0,0,308,205,15,15,p);
        p.setShader(null);
        c.restore();
    }
    private void drawFooter(Canvas c){
        rounded(c,227,1015,1640,2,0,0x665c878a);
        label(c,footer,230,1052,19,PALE,medium,false);
        boolean pictureStatus=savingPictures||pictureStore!=null&&!downloading;
        String progress=pictureStatus?pictureProgress:videoProgress;
        if(downloading){
            float fraction=pictureStatus?pictureFraction:videoFraction;
            if(fraction>0)rounded(c,227,1015,1640f*fraction,3,0,GOLD);
        }else if(pictureStatus?pictureInterrupted:videoInterrupted){
            progress="Download interrupted • resume when ready";
        }
        label(c,fit(progress,20,850),1865,1051,20,GOLD,medium,false,true);
    }
    private String fit(String value,int size,int width){
        String key=size+":"+width+":"+value;
        String result=fitted.get(key);
        if(result==null){
            textPaint.setTypeface(medium);
            textPaint.setTextSize(size);
            result=TextUtils.ellipsize(value,textPaint,width,TextUtils.TruncateAt.END).toString();
            fitted.put(key,result);
        }
        return result;
    }
    private void pill(Canvas c,int x,int y,String t,int color){
        p.setTypeface(medium);p.setTextSize(19);
        int w=(int)p.measureText(t)+37;
        rounded(c,x,y,w,43,18,color);
        label(c,t,x+18,y+28,19,CREAM,medium,false);
    }
    private void rect(Canvas c,float x,float y,float w,float h,int color,float radius){
        p.setColor(color);p.setShader(null);
        if(radius>0)c.drawRoundRect(x,y,x+w,y+h,radius,radius,p);
        else c.drawRect(x,y,x+w,y+h,p);
    }
    private void rounded(Canvas c,float x,float y,float w,float h,float radius,int color){rect(c,x,y,w,h,color,radius);}
    private void outline(Canvas c,float x,float y,float w,float h,float radius,int color,float thick){
        p.setShader(null);p.setColor(color);p.setStrokeWidth(thick);p.setStyle(Paint.Style.STROKE);
        c.drawRoundRect(x,y,x+w,y+h,radius,radius,p);p.setStyle(Paint.Style.FILL);
    }
    private void label(Canvas c,String text,float x,float baseline,float size,int color,Typeface font,boolean center){
        p.setShader(null);p.setColor(color);p.setTypeface(font);p.setTextSize(size);
        p.setTextAlign(center?Paint.Align.CENTER:Paint.Align.LEFT);
        c.drawText(text,x,baseline,p);p.setTextAlign(Paint.Align.LEFT);
    }
    private void label(Canvas c,String text,float right,float baseline,float size,int color,Typeface font,boolean center,boolean alignRight){
        p.setShader(null);p.setColor(color);p.setTypeface(font);p.setTextSize(size);
        p.setTextAlign(Paint.Align.RIGHT);
        c.drawText(text,right,baseline,p);p.setTextAlign(Paint.Align.LEFT);
    }
    private void image(Canvas canvas,Bitmap bitmap,float x,float y,float width,float height,float radius){
        BitmapShader shader=shaders.get(bitmap);
        if(shader==null){
            shader=new BitmapShader(bitmap,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);
            shaders.put(bitmap,shader);
        }
        float scale=Math.max(width/bitmap.getWidth(),height/bitmap.getHeight());
        imageMatrix.setScale(scale,scale);
        imageMatrix.postTranslate(x+(width-bitmap.getWidth()*scale)/2,y+(height-bitmap.getHeight()*scale)/2);
        shader.setLocalMatrix(imageMatrix);
        p.setColor(Color.WHITE);
        p.setShader(shader);
        canvas.drawRoundRect(x,y,x+width,y+height,radius,radius,p);
        p.setShader(null);
    }
    @Override public boolean onKeyDown(int key,KeyEvent e){
        switch(key){
            case KeyEvent.KEYCODE_MENU:
                actions.tools(selected(),pictureStore!=null&&!photos.isEmpty()?photos.get(photoIndex):null);return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if(focus==2&&buttonIndex>0){buttonIndex--;invalidate();return true;}
                if(focus==1)headerIndex=categoryIndex;
                focus=Math.max(0,focus-1);invalidate();return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if(focus>=1){
                    buttonIndex=focus==1?0:Math.min(3,buttonIndex+1);
                    focus=2;invalidate();return true;
                }
                focus=Math.min(2,focus+1);invalidate();return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if(focus==0)moveHeader(-1);
                else if(focus==1)move(-1);
                else buttonIndex=(buttonIndex+3)%4;
                if(!awaitingSelection)invalidate();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if(focus==0)moveHeader(1);
                else if(focus==1)move(1);
                else buttonIndex=(buttonIndex+1)%4;
                if(!awaitingSelection)invalidate();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                activate();return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if(pictureStore!=null)move(1);
                else if(selected()!=null)actions.play(selected());
                return true;
            default:return super.onKeyDown(key,e);
        }
    }
    /** LEFT/RIGHT on the header switches category at once; AMBIENT waits for OK. */
    private void moveHeader(int d){
        headerIndex=Math.floorMod(headerIndex+d,TOOLS_CHIP+1);
        if(headerIndex<AMBIENT_CHIP)selectCategory(headerIndex);
        invalidate();
    }
    private void activate(){
        if(focus==0){
            if(headerIndex==AMBIENT_CHIP){actions.ambient(null,0);return;}
            if(headerIndex==ADD_CHIP){actions.addPlace();return;}
            if(headerIndex==TOOLS_CHIP){actions.tools(selected(),pictureStore!=null&&!photos.isEmpty()?photos.get(photoIndex):null);return;}
            selectCategory(categoryIndex);focus=1;return;
        }
        Scene s=selected();if(s==null)return;
        if(pictureStore!=null){
            if(focus==1){actions.ambient(s,photoIndex);}
            else if(buttonIndex==1){actions.ambient(null,0);}
            else if(buttonIndex==0)closePictures();
            else if(buttonIndex==2){
                if(!photos.isEmpty())actions.removePictures(s);
                else actions.downloadPictures(s);
            }
            else if(buttonIndex==3)actions.downloadPictures(null);
            return;
        }
        if(focus==1 || buttonIndex==0)actions.play(s);
        else if(buttonIndex==1)showPictures(s);
        else if(buttonIndex==2)actions.download(s);
        else actions.downloadAllOrPause();
    }
    @Override public boolean onHoverEvent(MotionEvent e){
        if(e.getAction()==MotionEvent.ACTION_HOVER_MOVE){
            float x=e.getX()*1920/getWidth(),y=e.getY()*1080/getHeight();
            if(y>57&&y<114&&x>CHIP_X){
                hover=chipAt(x);
            }else hover=-1;
            invalidate();return true;
        }
        return super.onHoverEvent(e);
    }
    @Override public boolean onTouchEvent(MotionEvent e){
        float x=e.getX()*1920/getWidth(),y=e.getY()*1080/getHeight();
        if(e.getAction()==MotionEvent.ACTION_DOWN){downX=x;downY=y;return true;}
        if(e.getAction()==MotionEvent.ACTION_UP){
            if(Math.abs(x-downX)>75){focus=1;move(x<downX?1:-1);return true;}
            if(y>=57&&y<=114&&x>=CHIP_X){
                int chip=chipAt(x);
                focus=0;headerIndex=chip;
                if(chip==AMBIENT_CHIP)actions.ambient(null,0);
                else if(chip==ADD_CHIP)actions.addPlace();
                else if(chip==TOOLS_CHIP)actions.tools(selected(),pictureStore!=null&&!photos.isEmpty()?photos.get(photoIndex):null);
                else selectCategory(chip);
                invalidate();return true;
            }
            if(y>=789&&y<=994&&x>=228){
                if(pictureStore!=null){
                    int target=photoIndex/5*5+(int)((x-228)/328);
                    if(target<photos.size()){
                        if(target==photoIndex&&focus==1)actions.ambient(selected(),photoIndex);
                        else{photoIndex=target;focus=1;invalidate();}
                    }
                    return true;
                }
                int target=(index/5)*5+(int)((x-228)/328);
                if(target>=0&&target<visible.size()){
                    if(target==index&&focus==1)activate();
                    else{index=target;focus=1;selectionUpdated();actions.sceneChanged(selected());actions.categoryChanged(firstToResolve());}
                }
                return true;
            }
            if(x>=1404&&x<=1833&&y>=430&&y<=682 && ((int)y-430)%65<57){
                focus=2;buttonIndex=((int)y-430)/65;activate();invalidate();return true;
            }
            if(x>220&&x<1340&&y>157&&y<715){focus=1;activate();return true;}
        }
        return true;
    }
    @Override public boolean onGenericMotionEvent(MotionEvent event){
        if(event.getAction()==MotionEvent.ACTION_SCROLL){
            float delta=event.getAxisValue(MotionEvent.AXIS_VSCROLL);
            if(delta!=0){focus=1;move(delta>0?-1:1);return true;}
        }
        return super.onGenericMotionEvent(event);
    }
    void close(){
        pauseUpdates();
        closed=true;
        removeCallbacks(updateScene);
        removeCallbacks(reloadPictures);
        models.shutdownNow();
        packs.shutdownNow();
        posters.shutdown();
        shaders.evictAll();
    }
}
