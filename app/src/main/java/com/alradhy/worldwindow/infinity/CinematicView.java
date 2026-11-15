package com.alradhy.worldwindow.infinity;

import android.content.Context;
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
        void download(Scene scene);
        void downloadAllOrPause();
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
    private int hover=-1;
    private float downX,downY;
    private final Typeface heading=Typeface.create("sans-serif-light",Typeface.NORMAL);
    private final Typeface medium=Typeface.create("sans-serif-medium",Typeface.NORMAL);
    private final DateTimeFormatter timeFormat=DateTimeFormatter.ofPattern("HH:mm",Locale.US);

    public CinematicView(Context c,LibraryStore store,Actions listener){
        super(c);library=store;actions=listener;posters=new PosterLoader(store);
        visible.addAll(store.scenes);
        setFocusable(true);setFocusableInTouchMode(true);setLayerType(View.LAYER_TYPE_HARDWARE,null);
    }
    public Scene selected(){return visible.isEmpty()?null:visible.get(Math.max(0,Math.min(index,visible.size()-1)));}
    public List<Scene> firstToResolve(){
        ArrayList<Scene> result=new ArrayList<>();
        int start=(index/5)*5;
        for(int i=start;i<Math.min(start+5,visible.size());i++)result.add(visible.get(i));
        return result;
    }
    public void selectCategory(int i){
        categoryIndex=Math.max(0,Math.min(SceneCatalog.CATEGORIES.length-1,i));
        visible.clear();visible.addAll(library.category(SceneCatalog.CATEGORIES[categoryIndex]));
        index=0;invalidate();actions.categoryChanged(firstToResolve());
        Scene s=selected();if(s!=null)actions.sceneChanged(s);
    }
    private void move(int d){
        if(visible.isEmpty())return;
        index=(index+d+visible.size())%visible.size();
        invalidate();actions.categoryChanged(firstToResolve());
        Scene s=selected();if(s!=null)actions.sceneChanged(s);
    }
    @Override protected void onDraw(Canvas raw){
        super.onDraw(raw);
        float sx=getWidth()/1920f,sy=getHeight()/1080f;
        raw.save();raw.scale(sx,sy);
        paintBackground(raw);
        drawRail(raw);
        drawHeader(raw);
        Scene s=selected();
        if(s!=null){drawHero(raw,s);drawDetails(raw,s);}
        drawStrip(raw);drawFooter(raw);
        raw.restore();
        postInvalidateDelayed(15000);
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
        label(c,"∞",634,110,54,GOLD,heading,false);
        label(c,"CINEMATIC EXPLORATION",700,108,17,PALE,medium,false);
        for(int i=0;i<SceneCatalog.CATEGORIES.length;i++){
            int x=1024+i*172;
            boolean current=i==categoryIndex;
            rounded(c,x,57,161,57,16,current?0x668db3af:0x55203843);
            if(current||focus==0&&hover==i)outline(c,x,57,161,57,16,current?GOLD:0xff6b9da7,2);
            label(c,SceneCatalog.CATEGORIES[i],x+80,94,21,
                current?CREAM:PALE,medium,true);
        }
        rounded(c,224,132,1650,1,0,0x66789598);
    }
    private void drawHero(Canvas c,Scene s){
        rounded(c,222,157,1118,558,25,0xff0b303f);
        Bitmap bitmap=posters.peek(s,()->postInvalidate());
        if(bitmap!=null)image(c,bitmap,222,157,1118,558,25);
        else {
            p.setShader(new LinearGradient(220,150,1300,730,
                new int[]{0xff122d39,0xff255c6a,0xff0a2731},null,Shader.TileMode.CLAMP));
            c.drawRoundRect(222,157,1340,715,25,25,p);p.setShader(null);
            drawGlobe(c,840,410);
        }
        // Overlay: text stays readable regardless of photographic highlights.
        p.setShader(new LinearGradient(222,400,222,730,0x00101b24,0xe908171f,Shader.TileMode.CLAMP));
        c.drawRoundRect(222,157,1340,715,25,25,p);p.setShader(null);
        p.setShader(new LinearGradient(222,157,700,157,0x7704121a,0x00000000,Shader.TileMode.CLAMP));
        c.drawRoundRect(222,157,1340,715,25,25,p);p.setShader(null);
        pill(c,255,194,s.isOffline()?"● OFFLINE COLLECTION":s.isVideo()?"▶ CINEMATIC FOOTAGE":"◈ WORLD GALLERY",
             s.isOffline()?0xff408e81:0xaa162e3b);
        String title=fit(s.title,76,950);
        label(c,title,269,567,Math.min(76,title.length()>20?62:76),CREAM,heading,false);
        label(c,s.arabic,274,628,37,0xffe9c891,medium,false);
        label(c,s.subtitle,275,676,25,0xffd5e5e5,heading,false);
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
    private void drawDetails(Canvas c,Scene s){
        rounded(c,1360,157,516,558,26,0xdd102b35);
        outline(c,1360,157,516,558,26,0x6669979b,1.8f);
        label(c,"A WINDOW TO WONDER",1401,207,21,GOLD,medium,false);
        label(c,String.format(Locale.US,"%02d  /  %02d",index+1,visible.size()),
             1815,207,22,PALE,medium,true);
        label(c,"YOUR NEXT DESTINATION",1402,277,20,0xff9bb7bd,medium,false);
        label(c,fit(s.title,52,436),1402,344,52,CREAM,heading,false);
        String clock;
        try{clock=ZonedDateTime.now(ZoneId.of(s.zone)).format(timeFormat);}
        catch(Exception e){clock="--:--";}
        label(c,clock,1404,414,48,GOLD,medium,false);
        label(c,"LOCAL TIME   •   "+s.zone.replace('_',' '),1560,410,20,PALE,medium,false);
        boolean downloading=getContext().getSharedPreferences("infinity_downloads",Context.MODE_PRIVATE)
            .getBoolean("active",false);
        action(c,0,1404,438,"▶    EXPLORE NOW",false);
        action(c,1,1404,526,s.isOffline()?"×    REMOVE OFFLINE COPY":"↓    SAVE THIS SCENE",false);
        action(c,2,1404,614,downloading?"Ⅱ    PAUSE DOWNLOADS":"⇩    DOWNLOAD ALL 100",true);
        label(c,s.status(),1408,703,18,s.isOffline()?0xff91e0c4:0xffb9ccd0,medium,false);
    }
    private void action(Canvas c,int n,int x,int y,String name,boolean warm){
        int fill=warm?0xffa87b42:0xff254a58;
        rounded(c,x,y,429,72,16,fill);
        if(focus==2&&buttonIndex==n)outline(c,x-3,y-3,435,78,17,GOLD,4);
        label(c,name,x+25,y+45,24,warm?0xff0c1a1f:CREAM,medium,false);
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
            Bitmap bmp=posters.peek(s,()->postInvalidate());
            if(bmp!=null)image(c,bmp,x,y,308,205,15);
            else{
                p.setShader(new LinearGradient(x,y,x+308,y+205,
                    new int[]{0xff255264,0xff102e3d},null,Shader.TileMode.CLAMP));
                c.drawRoundRect(x,y,x+308,y+205,15,15,p);p.setShader(null);
                p.setStyle(Paint.Style.STROKE);p.setColor(0x447ed4d8);p.setStrokeWidth(2);
                c.drawCircle(x+154,y+80,46,p);p.setStyle(Paint.Style.FILL);
            }
            p.setShader(new LinearGradient(x,y+80,x,y+205,0x00101920,0xee081821,Shader.TileMode.CLAMP));
            c.drawRoundRect(x,y,x+308,y+205,15,15,p);p.setShader(null);
            label(c,fit(s.title,29,280),x+16,y+165,29,CREAM,medium,false);
            label(c,s.isOffline()?"● SAVED":s.isVideo()?"▶ VIDEO":"◇ "+s.category,
                x+17,y+188,15,s.isOffline()?0xff8edac5:GOLD,medium,false);
            if(j==index)outline(c,x-3,y-3,314,211,17,
                focus==1?GOLD:0xffa5bebb,focus==1?4.3f:2.5f);
        }
    }
    private void drawFooter(Canvas c){
        rounded(c,227,1015,1640,2,0,0x665c878a);
        String summary=library.scenes.size()+" DESTINATIONS     •     "+library.offlineCount()+
            " OFFLINE     •     "+library.human(library.offlineBytes())+" SAVED";
        label(c,summary,230,1052,19,PALE,medium,false);
        String progress=getContext().getSharedPreferences("infinity_downloads",Context.MODE_PRIVATE)
            .getString("status","ONLINE DISCOVERY • OFFLINE FREEDOM");
        label(c,fit(progress,20,850),1865,1051,20,GOLD,medium,true);
    }
    private String fit(String str,int textSize,int maxW){
        p.setTypeface(medium);p.setTextSize(textSize);
        if(p.measureText(str)<=maxW)return str;
        while(str.length()>4&&p.measureText(str+"…")>maxW)str=str.substring(0,str.length()-1);
        return str+"…";
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
    private void image(Canvas c,Bitmap b,float x,float y,float w,float h,float r){
        if(b==null||b.isRecycled())return;
        float ratio=Math.max(w/b.getWidth(),h/b.getHeight());
        int sw=Math.min(b.getWidth(),Math.round(w/ratio));
        int sh=Math.min(b.getHeight(),Math.round(h/ratio));
        Rect source=new Rect((b.getWidth()-sw)/2,(b.getHeight()-sh)/2,
            (b.getWidth()+sw)/2,(b.getHeight()+sh)/2);
        c.save();Path clip=new Path();clip.addRoundRect(x,y,x+w,y+h,r,r,Path.Direction.CW);
        c.clipPath(clip);p.setColor(Color.WHITE);p.setShader(null);p.setAlpha(255);
        c.drawBitmap(b,source,new RectF(x,y,x+w,y+h),p);c.restore();
    }
    @Override public boolean onKeyDown(int key,KeyEvent e){
        switch(key){
            case KeyEvent.KEYCODE_DPAD_UP:
                focus=Math.max(0,focus-1);invalidate();return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                focus=Math.min(2,focus+1);invalidate();return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if(focus==0)selectCategory((categoryIndex+4)%5);
                else if(focus==1)move(-1);
                else buttonIndex=(buttonIndex+2)%3;
                invalidate();return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if(focus==0)selectCategory((categoryIndex+1)%5);
                else if(focus==1)move(1);
                else buttonIndex=(buttonIndex+1)%3;
                invalidate();return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                activate();return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if(selected()!=null)actions.play(selected());return true;
            default:return super.onKeyDown(key,e);
        }
    }
    private void activate(){
        if(focus==0){selectCategory(categoryIndex);focus=1;return;}
        Scene s=selected();if(s==null)return;
        if(focus==1 || buttonIndex==0)actions.play(s);
        else if(buttonIndex==1)actions.download(s);
        else actions.downloadAllOrPause();
    }
    @Override public boolean onHoverEvent(MotionEvent e){
        if(e.getAction()==MotionEvent.ACTION_HOVER_MOVE){
            float x=e.getX()*1920/getWidth(),y=e.getY()*1080/getHeight();
            if(y>57&&y<114&&x>1024){
                hover=Math.min(4,Math.max(0,((int)x-1024)/172));
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
            if(y>=57&&y<=114&&x>=1024){focus=0;selectCategory(Math.min(4,((int)x-1024)/172));return true;}
            if(y>=789&&y<=994&&x>=228){
                int target=(index/5)*5+(int)((x-228)/328);
                if(target>=0&&target<visible.size()){
                    if(target==index&&focus==1)activate();
                    else{index=target;focus=1;invalidate();actions.sceneChanged(selected());actions.categoryChanged(firstToResolve());}
                }
                return true;
            }
            if(x>=1404&&x<=1833&&y>=438&&y<=690){
                focus=2;buttonIndex=Math.min(2,Math.max(0,((int)y-438)/88));activate();invalidate();return true;
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
    void close(){posters.shutdown();}
}
