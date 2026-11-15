package com.alradhy.worldwindow.infinity;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.view.*;
import android.widget.Toast;
import java.util.*;
import java.util.concurrent.*;

/** Launcher owns only UI and lightweight discovery; the service owns downloads. */
public final class MainActivity extends Activity implements CinematicView.Actions {
    private LibraryStore library;
    private MediaResolver resolver;
    private CinematicView surface;
    private SharedPreferences browsing;
    private final ExecutorService discovery=Executors.newSingleThreadExecutor();
    private final Set<String> queued=Collections.synchronizedSet(new HashSet<>());
    private final Handler handler=new Handler(Looper.getMainLooper());
    private boolean resumed=false;
    private final Runnable refresh=new Runnable(){
        @Override public void run(){
            if(resumed&&surface!=null){
                surface.refreshPictures();
                surface.invalidate();
                handler.postDelayed(this,2500);
            }
        }
    };
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_STABLE|
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        library=LibraryStore.shared(this);
        resolver=new MediaResolver(library);
        surface=new CinematicView(this,library,this);
        browsing=getSharedPreferences("infinity_browsing",MODE_PRIVATE);
        surface.restoreSelection(browsing.getString("category","ALL"),browsing.getString("scene",""));
        setContentView(surface);
        surface.requestFocus();
        prefetch(surface.firstToResolve());
    }
    private void prefetch(List<Scene> items){
        for(Scene scene:items){
            if(scene.hasSource()||scene.isOffline()||!queued.add(scene.id))continue;
            discovery.execute(()->{
                try{resolver.resolve(scene);}
                finally{
                    queued.remove(scene.id);
                    if(surface!=null)surface.postInvalidate();
                }
            });
        }
    }
    @Override public void categoryChanged(List<Scene> visible){prefetch(visible);}
    @Override public void sceneChanged(Scene scene){prefetch(Collections.singletonList(scene));}
    @Override public void play(Scene scene){
        if(scene==null)return;
        if(scene.hasSource()||scene.isOffline()){openPlayer(scene);return;}
        Toast.makeText(this,"Finding media for "+scene.title+"…",Toast.LENGTH_SHORT).show();
        discovery.execute(()->{
            boolean ok=resolver.resolve(scene);
            runOnUiThread(()->{
                if(ok)openPlayer(scene);
                else Toast.makeText(this,"No compatible media yet. Choose another scene.",Toast.LENGTH_LONG).show();
                surface.invalidate();
            });
        });
    }
    private void openPlayer(Scene scene){
        Intent i=new Intent(this,ScenePlayerActivity.class);
        i.putExtra("scene_id",scene.id);
        i.putExtra("category",scene.category);
        startActivity(i);
    }
    @Override public void downloadPictures(Scene scene){
        Intent intent=new Intent(this,OfflineDownloadService.class);
        if(OfflineDownloadService.isRunning()){
            if(scene==null && OfflineDownloadService.isSavingPictures()){
                startService(intent.setAction(OfflineDownloadService.PAUSE));
            }else{
                Toast.makeText(this,"A download is already running",Toast.LENGTH_SHORT).show();
            }
            return;
        }
        requestNotices();
        intent.setAction(scene==null?OfflineDownloadService.PICTURES_ALL:OfflineDownloadService.PICTURES_ONE);
        if(scene!=null)intent.putExtra(OfflineDownloadService.EXTRA_ID,scene.id);
        startForegroundService(intent);
    }
    @Override public void download(Scene scene){
        if(scene.isOffline()){
            new AlertDialog.Builder(this)
                .setTitle("Remove this offline scene?")
                .setMessage(scene.title+" • the local file will be deleted, but the destination remains in your library.")
                .setNegativeButton("Keep",null)
                .setPositiveButton("Remove",(dialog,which)->{
                    boolean done=library.deleteOffline(scene);
                    Toast.makeText(this,done?"Offline copy removed":"Could not remove a busy file",Toast.LENGTH_LONG).show();
                    surface.invalidate();
                }).show();
            return;
        }
        if(OfflineDownloadService.isRunning()){
            Toast.makeText(this,"A download is already running",Toast.LENGTH_SHORT).show();
            return;
        }
        requestNotices();
        Intent i=new Intent(this,OfflineDownloadService.class);
        i.setAction(OfflineDownloadService.ONE);
        i.putExtra(OfflineDownloadService.EXTRA_ID,scene.id);
        startForegroundService(i);
        Toast.makeText(this,"Saving "+scene.title+" • you can switch apps",Toast.LENGTH_LONG).show();
    }
    @Override public void downloadAllOrPause(){
        requestNotices();
        boolean running=OfflineDownloadService.isRunning();
        Intent i=new Intent(this,OfflineDownloadService.class);
        i.setAction(running?OfflineDownloadService.PAUSE:OfflineDownloadService.ALL);
        if(running)startService(i);
        else startForegroundService(i);
        Toast.makeText(this,running?"Pausing downloads safely":"Download All started • safe to use other apps",
            Toast.LENGTH_LONG).show();
    }
    private void requestNotices(){
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},104);
    }
    @Override protected void onResume(){
        super.onResume();resumed=true;handler.removeCallbacks(refresh);handler.post(refresh);
        if(surface!=null)surface.requestFocus();
    }
    @Override protected void onPause(){
        resumed=false;
        handler.removeCallbacks(refresh);
        Scene selected=surface.selected();
        if(selected!=null){
            browsing.edit().putString("category",surface.selectedCategory()).putString("scene",selected.id).apply();
        }
        super.onPause();
    }
    @Override public void onBackPressed(){if(!surface.closePictures())finish();}
    @Override protected void onDestroy(){
        handler.removeCallbacksAndMessages(null);
        discovery.shutdownNow();
        if(surface!=null)surface.close();
        super.onDestroy();
    }
}
