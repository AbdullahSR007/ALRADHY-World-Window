package com.alradhy.worldwindow.infinity;

import android.app.Activity;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

/** Full-bleed cinema. Videos via Media3; stills via gentle Ken Burns motion. */
public final class ScenePlayerActivity extends Activity {
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService discovery=Executors.newSingleThreadExecutor();
    private LibraryStore library;
    private MediaResolver resolver;
    private PosterLoader posters;
    private FrameLayout root;
    private PlayerView playerView;
    private ImageView photo;
    private TextView heading,subheading,credits,clock;
    private ExoPlayer player;
    private ArrayList<Scene> playlist;
    private int position=0,generation=0;
    private float touchDownX;
    private final DateTimeFormatter clockFmt=DateTimeFormatter.ofPattern("HH:mm",Locale.US);
    private final Runnable tick=new Runnable(){
        @Override public void run(){updateClock();main.postDelayed(this,15000);}
    };
    private final Runnable nextPhoto=new Runnable(){
        @Override public void run(){next(1);}
    };
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_FULLSCREEN|
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        library=LibraryStore.shared(this);resolver=new MediaResolver(library);posters=new PosterLoader(library);
        String category=getIntent().getStringExtra("category");
        if(category==null)category="ALL";
        playlist=new ArrayList<>(library.category(category));
        if(playlist.isEmpty())playlist=new ArrayList<>(library.scenes);
        String id=getIntent().getStringExtra("scene_id");
        for(int i=0;i<playlist.size();i++)if(playlist.get(i).id.equals(id)){position=i;break;}
        makeScreen();show();
    }
    private void makeScreen(){
        root=new FrameLayout(this);root.setBackgroundColor(0xff050f18);
        photo=new ImageView(this);photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(photo,new FrameLayout.LayoutParams(-1,-1));
        playerView=new PlayerView(this);playerView.setUseController(true);
        playerView.setControllerAutoShow(false);playerView.setControllerShowTimeoutMs(3500);
        root.addView(playerView,new FrameLayout.LayoutParams(-1,-1));
        View shade=new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
            new int[]{0xed061019,0x7707131b,0x0007131b}));
        FrameLayout.LayoutParams shadeParams=new FrameLayout.LayoutParams(-1,dp(330),Gravity.BOTTOM);
        root.addView(shade,shadeParams);
        LinearLayout info=new LinearLayout(this);info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(56),dp(18),dp(56),dp(40));
        FrameLayout.LayoutParams infoParams=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);
        root.addView(info,infoParams);
        heading=text(44,0xfff4f2e7,true);
        subheading=text(23,0xffd5ae71,false);
        credits=text(16,0xffa2b8bc,false);
        info.addView(heading);info.addView(subheading);info.addView(credits);
        clock=text(19,0xffe7d6ad,true);
        FrameLayout.LayoutParams clockP=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.RIGHT);
        clockP.setMargins(0,dp(36),dp(58),0);
        root.addView(clock,clockP);
        setContentView(root);
    }
    private TextView text(int sp,int color,boolean strong){
        TextView t=new TextView(this);t.setTextColor(color);t.setTextSize(sp);
        t.setTypeface(android.graphics.Typeface.create(strong?"sans-serif-medium":"sans-serif-light",0));
        t.setPadding(0,dp(3),0,dp(6));return t;
    }
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+0.5f);}
    private void show(){
        main.removeCallbacks(nextPhoto);
        releasePlayer();
        int run=++generation;
        Scene s=playlist.get(position);
        heading.setText(s.title+"     "+s.arabic);
        subheading.setText(s.subtitle+"   •   "+s.status());
        credits.setText("← / → NEXT SCENE       •       BACK TO COLLECTION       •       "+s.license+
                (s.credit.isEmpty()?"":"   |   "+s.credit));
        updateClock();
        photo.animate().cancel();photo.setScaleX(1);photo.setScaleY(1);
        if(!s.hasSource()&&!s.isOffline()){
            photo.setVisibility(View.VISIBLE);playerView.setVisibility(View.GONE);
            subheading.setText("Finding an available source…");
            discovery.execute(()->{
                boolean ok=resolver.resolve(s);
                runOnUiThread(()->{
                    if(generation!=run||isFinishing())return;
                    if(ok)show();
                    else{
                        subheading.setText("Source unavailable • press RIGHT for another destination");
                        main.postDelayed(nextPhoto,10000);
                    }
                });
            });
            return;
        }
        if(s.isVideo())showVideo(s);
        else showPhoto(s,run);
    }
    private void showVideo(Scene s){
        photo.setVisibility(View.GONE);playerView.setVisibility(View.VISIBLE);
        try{
            String uri=s.isOffline()?Uri.fromFile(new java.io.File(s.localMedia)).toString():s.mediaUrl;
            player=new ExoPlayer.Builder(this).build();
            playerView.setPlayer(player);
            player.setMediaItem(MediaItem.fromUri(uri));
            player.addListener(new Player.Listener(){
                @Override public void onPlaybackStateChanged(int state){
                    if(state==Player.STATE_ENDED)runOnUiThread(()->next(1));
                }
                @Override public void onPlayerError(androidx.media3.common.PlaybackException e){
                    runOnUiThread(()->{
                        subheading.setText("Playback unsupported on this device • try next scene");
                        main.postDelayed(nextPhoto,7000);
                    });
                }
            });
            player.prepare();player.play();
        }catch(Exception e){
            subheading.setText("Unable to play this source: "+e.getClass().getSimpleName());
            main.postDelayed(nextPhoto,8000);
        }
    }
    private void showPhoto(Scene s,int run){
        photo.setVisibility(View.VISIBLE);playerView.setVisibility(View.GONE);
        Bitmap b=posters.peek(s,()->runOnUiThread(()->{
            if(run==generation)showPhotoBitmap(s,run);
        }));
        if(b!=null){photo.setImageBitmap(b);photo.animate().scaleX(1.10f).scaleY(1.10f).setDuration(23000).start();}
        else photo.setBackgroundColor(0xff123743);
        main.postDelayed(nextPhoto,24000);
    }
    private void showPhotoBitmap(Scene s,int run){
        if(generation!=run)return;
        Bitmap img=posters.peek(s,()->{});
        if(img!=null){
            photo.setImageBitmap(img);
            photo.animate().cancel();photo.setScaleX(1f);photo.setScaleY(1f);
            photo.animate().scaleX(1.10f).scaleY(1.10f).setDuration(23000).start();
        }
    }
    private void updateClock(){
        if(playlist==null||playlist.isEmpty())return;
        Scene s=playlist.get(position);
        try{
            String time=ZonedDateTime.now(ZoneId.of(s.zone)).format(clockFmt);
            clock.setText("◷  "+time+"   •   "+s.zone.replace('_',' '));
        }catch(Exception e){clock.setText("ALRADHY • INFINITY");}
    }
    private void next(int step){
        if(playlist.isEmpty())return;
        position=(position+step+playlist.size())%playlist.size();show();
    }
    @Override public boolean onKeyDown(int key,KeyEvent e){
        if(key==KeyEvent.KEYCODE_DPAD_RIGHT){next(1);return true;}
        if(key==KeyEvent.KEYCODE_DPAD_LEFT){next(-1);return true;}
        if(key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE){
            if(player!=null){if(player.isPlaying())player.pause();else player.play();return true;}
            next(1);return true;
        }
        if(key==KeyEvent.KEYCODE_DPAD_UP&&playerView.getVisibility()==View.VISIBLE){
            playerView.showController();return true;
        }
        return super.onKeyDown(key,e);
    }
    @Override public boolean onTouchEvent(android.view.MotionEvent e){
        if(e.getAction()==MotionEvent.ACTION_DOWN){touchDownX=e.getX();return true;}
        if(e.getAction()==MotionEvent.ACTION_UP){
            float shift=e.getX()-touchDownX;
            if(Math.abs(shift)>70)next(shift<0?1:-1);
            else if(player==null)next(1);
            else playerView.showController();
            return true;
        }
        return true;
    }
    @Override public void onBackPressed(){finish();}
    private void releasePlayer(){
        if(player!=null){playerView.setPlayer(null);player.release();player=null;}
    }
    @Override protected void onResume(){super.onResume();main.removeCallbacks(tick);main.post(tick);}
    @Override protected void onPause(){
        main.removeCallbacks(tick);main.removeCallbacks(nextPhoto);
        if(player!=null)player.pause();
        super.onPause();
    }
    @Override protected void onDestroy(){
        ++generation;main.removeCallbacksAndMessages(null);releasePlayer();
        discovery.shutdownNow();posters.shutdown();super.onDestroy();
    }
}
