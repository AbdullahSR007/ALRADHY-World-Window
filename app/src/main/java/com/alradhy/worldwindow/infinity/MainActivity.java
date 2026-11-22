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
    private final ExecutorService stats=Executors.newSingleThreadExecutor();
    private final ExecutorService worldWorker=Executors.newSingleThreadExecutor();
    private final ExecutorService placeSearch=Executors.newSingleThreadExecutor();
    private WorldStore world;
    private final Set<String> enriched=new HashSet<>();
    private final Set<String> queued=Collections.synchronizedSet(new HashSet<>());
    private final Handler handler=new Handler(Looper.getMainLooper());
    private SharedPreferences videoProgress,pictureProgress;
    private boolean resumed,counting,countAgain;
    private final Runnable progressChanged=this::updateProgress;
    private final Runnable summaryChanged=this::countPictures;
    private final SharedPreferences.OnSharedPreferenceChangeListener progressListener=(prefs,key)->{
        handler.removeCallbacks(progressChanged);
        handler.postDelayed(progressChanged,80);
    };
    private final BroadcastReceiver packChanges=new BroadcastReceiver(){
        @Override public void onReceive(Context context,Intent intent){
            surface.packChanged(intent.getStringExtra("scene_id"));
            scheduleSummary();
        }
    };

    private void updateProgress(){
        if(!resumed)return;
        surface.setProgress(videoProgress,pictureProgress);
        surface.refreshScene();
        if(!OfflineDownloadService.isSavingPictures())scheduleSummary();
    }

    private void scheduleSummary(){
        if(!resumed||handler.hasCallbacks(summaryChanged))return;
        handler.postDelayed(summaryChanged,350);
    }

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_STABLE|
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        library=LibraryStore.shared(this);
        world=new WorldStore(this);
        resolver=new MediaResolver(library);
        surface=new CinematicView(this,library,this);
        videoProgress=getSharedPreferences("infinity_downloads",MODE_PRIVATE);
        pictureProgress=getSharedPreferences("infinity_pictures",MODE_PRIVATE);
        browsing=getSharedPreferences("infinity_browsing",MODE_PRIVATE);
        surface.restoreSelection(browsing.getString("category","ALL"),browsing.getString("scene",""));
        setContentView(surface);
        surface.requestFocus();
        prefetch(surface.firstToResolve());
        // Upgrade previously added places' descriptions without touching their media.
        worldWorker.execute(()->{
            for(String id:world.customIds()){
                if(Thread.currentThread().isInterrupted())return;
                Scene scene=library.byId(id);
                if(scene==null||world.info(scene).has("extractAr"))continue;
                try{
                    world.refresh(scene);
                    runOnUiThread(surface::refreshScene);
                }catch(Exception ignored){/* Offline: retry on the next launch. */}
            }
        });
    }

    private void prefetch(List<Scene> items){
        for(Scene scene:items){
            if(scene.hasSource()||!queued.add(scene.id))continue;
            discovery.execute(()->{
                try{if(!scene.isOffline())resolver.resolve(scene);}
                finally{
                    queued.remove(scene.id);
                    runOnUiThread(()->{
                        if(!isDestroyed()&&surface.firstToResolve().contains(scene))surface.refreshScene();
                    });
                }
            });
        }
    }

    private void countPictures(){
        if(!resumed)return;
        if(counting){countAgain=true;return;}
        counting=true;
        stats.execute(()->{
            try{
                GalleryStore.Summary summary=GalleryStore.summarize(this,library.scenes);
                String footer=library.scenes.size()+" DESTINATIONS   •   "+library.offlineCount()+
                    " OFFLINE   •   "+summary.photos+" PHOTOS   •   "+
                    library.human(library.offlineBytes()+summary.bytes)+" SAVED";
                handler.post(()->{if(resumed)surface.setSummary(summary,footer);});
            }finally{
                handler.post(()->{
                    counting=false;
                    if(countAgain){countAgain=false;scheduleSummary();}
                });
            }
        });
    }
    @Override public void ambient(Scene scene,int start){
        world.prefs.edit().putInt("mix",0).apply();
        Intent intent=new Intent(this,AmbientActivity.class);
        if(scene!=null){
            intent.putExtra(AmbientActivity.EXTRA_ANCHOR_SCENE,scene.id);
            intent.putExtra(AmbientActivity.EXTRA_START,start);
        }
        startActivity(intent);
    }
    private void launchAmbient(Scene scene,int start){
        Intent intent=new Intent(this,AmbientActivity.class);
        if(scene!=null){
            intent.putExtra(AmbientActivity.EXTRA_SCENE,scene.id);
            intent.putExtra(AmbientActivity.EXTRA_START,start);
        }
        startActivity(intent);
    }
    @Override public void removePictures(Scene scene){
        if(scene==null)return;
        new AlertDialog.Builder(this).setTitle("إدارة صور "+scene.title)
            .setItems(new String[]{"الاكتفاء بالصور المحفوظة","البحث عن صور إضافية (حتى ٢٠)","حذف صور هذا المكان"},(d,w)->{
                if(w==1)downloadPictures(scene);
                if(w==2)confirmRemovePictures(scene);
            }).setNegativeButton("إغلاق",null).show();
    }
    private void confirmRemovePictures(Scene scene){
        if(scene==null)return;
        if(OfflineDownloadService.isSavingPictures()){
            Toast.makeText(this,"Pause picture downloads before removing a pack",Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("Remove "+scene.title+" pictures?")
            .setMessage("The saved photos for this destination will be deleted. Videos, other packs and settings stay.")
            .setNegativeButton("Keep",null)
            .setPositiveButton("Remove",(dialog,which)->{
                if(OfflineDownloadService.isSavingPictures()){
                    Toast.makeText(this,"Picture downloads started • try again after pausing",Toast.LENGTH_LONG).show();
                    return;
                }
                boolean done=new GalleryStore(this,scene).delete();
                Toast.makeText(this,done?"Picture pack removed":"Some pictures could not be removed",Toast.LENGTH_LONG).show();
                surface.refreshPictures();
                countPictures();
                surface.invalidate();
            }).show();
    }
    @Override public void categoryChanged(List<Scene> visible){prefetch(visible);}
    @Override public void sceneChanged(Scene scene){
        prefetch(Collections.singletonList(scene));
        if(enriched.add(scene.id))worldWorker.execute(()->{
            try{world.refresh(scene);runOnUiThread(surface::refreshScene);}catch(Exception ignored){}
        });
    }
    private void message(String value){if(!isDestroyed())Toast.makeText(this,value,Toast.LENGTH_LONG).show();}
    @Override public void addPlace(){
        new AlertDialog.Builder(this).setTitle("اختر تصنيف المكان الجديد").setItems(WorldStore.CATEGORY_NAMES,(d,w)->searchPlace(WorldStore.PLACE_CATEGORIES[w])).setNegativeButton("إلغاء",null).show();
    }
    private void searchPlace(String category){
        android.widget.EditText input=new android.widget.EditText(this);
        input.setSingleLine(true);input.setHint(category.equals("SPACE")?"اسم كوكب أو سديم أو مجرة":"اسم مدينة أو معلم بالعربية أو الإنجليزية");
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(100)});
        new AlertDialog.Builder(this).setTitle("إضافة مكان — "+WorldStore.categoryName(category)).setView(input)
            .setNegativeButton("Cancel",null).setPositiveButton("Search",(d,w)->{
                String query=input.getText().toString().trim();
                if(query.length()<2){message("Enter at least two characters");return;}
                message("Searching for places…");
                placeSearch.execute(()->{
                    try{
                        List<org.json.JSONObject> found=WorldStore.search(query,category);
                        runOnUiThread(()->{
                            if(isDestroyed())return;
                            if(found.isEmpty()){message("No matching places. Try a city and country, or another spelling.");return;}
                            String[] names=new String[found.size()];
                            for(int i=0;i<names.length;i++)names[i]=found.get(i).optString("title");
                            new AlertDialog.Builder(this).setTitle("Choose the correct place").setItems(names,(dialog,which)->preparePlace(found.get(which),category)).setNegativeButton("Cancel",null).show();
                        });
                    }catch(Exception error){runOnUiThread(()->message("Search unavailable. Check your connection and try again."));}
                });
            }).show();
    }
    private void preparePlace(org.json.JSONObject place,String category){
        message("جارٍ تحميل الوصف العربي…");
        placeSearch.execute(()->{
            try{WorldStore.arabic(place);}catch(Exception ignored){}
            runOnUiThread(()->{if(!isDestroyed())confirmPlace(place,category);});
        });
    }
    private void confirmPlace(org.json.JSONObject place,String category){
        new AlertDialog.Builder(this).setTitle(place.optString("title"))
            .setMessage(place.optString("extractAr",WorldStore.NO_ARABIC)+"\n\nالتصنيف: "+WorldStore.categoryName(category)+"\nويكيبيديا • CC BY-SA. هل تريد إضافة المكان؟")
            .setNegativeButton("Cancel",null).setPositiveButton("Add place",(d,w)->{
                try{
                    for(Scene existing:library.scenes)if(existing.title.equalsIgnoreCase(place.optString("title"))){
                        surface.restoreSelection("ALL",existing.id);message("Already in your library");return;
                    }
                    Scene added=world.add(place,category);library.scenes.add(added);
                    surface.restoreSelection("MY WORLD",added.id);surface.invalidate();sceneChanged(added);
                    new AlertDialog.Builder(this).setTitle("Place added").setMessage("Save up to 20 distinct pictures from available sources? Progress and any missing pictures appear in Downloads.")
                        .setNegativeButton("Later",null).setPositiveButton("Save picture pack",(a,b)->downloadPictures(added)).show();
                }catch(Exception error){message(error.getMessage());}
            }).show();
    }
    @Override public void tools(Scene scene,GalleryStore.Photo photo){
        ArrayList<String> labels=new ArrayList<>();ArrayList<Runnable> commands=new ArrayList<>();
        if(scene!=null){
            labels.add(world.favourite(scene)?"Remove destination from favourites":"♥ Favourite this destination");
            commands.add(()->{world.toggle(scene);surface.restoreSelection(surface.selectedCategory(),scene.id);surface.invalidate();});
            if(photo!=null){labels.add(world.photoFavourite(scene,photo.file)?"Remove photo from favourites":"♥ Favourite this picture");commands.add(()->{world.togglePhoto(scene,photo.file);message("Picture favourite updated");});}
            if(world.isCustom(scene)){labels.add("حذف هذا المكان المضاف");commands.add(()->deletePlace(scene));}
            labels.add("عن المكان • الوصف العربي ومواقيت الشمس");commands.add(()->about(scene));
            labels.add("Slideshow: this destination only");commands.add(()->launchAmbient(scene,0));
        }
        labels.add("Downloads and storage");commands.add(this::downloadCenter);
        labels.add("الاستكمال التلقائي عند تنزيل الكل: "+(world.prefs.getBoolean("auto_pictures",false)?"تشغيل":"إيقاف"));
        commands.add(()->{boolean enabled=!world.prefs.getBoolean("auto_pictures",false);world.prefs.edit().putBoolean("auto_pictures",enabled).apply();message(enabled?"سيبحث تنزيل الكل عن صور إضافية للحزم الموجودة أيضًا":"تنزيل الكل سيحفظ الأماكن التي بلا صور فقط؛ يمكنك طلب المزيد يدويًا");});
        if(getSharedPreferences("infinity_queue",MODE_PRIVATE).getBoolean("pending",false)){
            labels.add("Resume last interrupted download");commands.add(()->{
                if(OfflineDownloadService.isRunning()){message("A download is already running");return;}
                requestNotices();startForegroundService(new Intent(this,OfflineDownloadService.class).setAction(OfflineDownloadService.RESUME));
            });
        }
        labels.add("Slideshow settings");commands.add(this::slideshowSettings);
        labels.add("Ambient mix");commands.add(this::ambientMix);
        labels.add(world.prefs.getBoolean("refreshing",false)?world.prefs.getString("world_status","Saving place information…"):"حفظ / تحديث الأوصاف العربية لجميع الأماكن");commands.add(this::refreshWorld);
        new AlertDialog.Builder(this).setTitle("INFINITY options").setItems(labels.toArray(new String[0]),(d,w)->commands.get(w).run()).setNegativeButton("Close",null).show();
    }
    private void deletePlace(Scene scene){
        new AlertDialog.Builder(this).setTitle("حذف "+scene.title+"؟")
            .setMessage("سيُحذف هذا المكان المضاف وتنزيلاته وصوره ومفضلته فقط. ستبقى الأماكن الأصلية وبقية مكتبتك كما هي.")
            .setNegativeButton("إلغاء",null).setPositiveButton("حذف المكان",(d,w)->{
                try{library.deleteCustom(scene);surface.restoreSelection("MY WORLD","");surface.invalidate();countPictures();message("تم حذف المكان المضاف");}
                catch(Exception error){message(error.getMessage());}
            }).show();
    }
    private void about(Scene scene){
        org.json.JSONObject info=world.info(scene);
        new AlertDialog.Builder(this).setTitle(scene.title)
            .setMessage(world.daylightArabic(scene)+"\n\n"+world.description(scene)+
                (info.optLong("pageidAr",0)>0?"\n\nويكيبيديا العربية • CC BY-SA 4.0\nhttps://ar.wikipedia.org/?curid="+info.optLong("pageidAr"):"\n\nوصف عربي ضمن التطبيق؛ يمكن تحديثه من مصدر عربي عند توفره.")+
                "\nمواقيت الشمس: Open-Meteo • CC BY 4.0 • محفوظة حتى ٧ أيام")
            .setNegativeButton("Close",null).setPositiveButton("تحديث الوصف",(d,w)->{
                message("Refreshing place information…");worldWorker.execute(()->{
                    try{world.refresh(scene);runOnUiThread(()->{if(!isDestroyed()){surface.refreshScene();about(scene);}});}
                    catch(Exception e){runOnUiThread(()->message("Refresh unavailable; saved facts retained"));}
                });
            }).show();
    }
    private void refreshWorld(){
        if(world.prefs.getBoolean("refreshing",false)){message("Place information refresh is already queued");return;}
        world.prefs.edit().putBoolean("refreshing",true).apply();message("Saving place facts and sun times in the background");
        worldWorker.execute(()->{
            int done=0,checked=0;
            try{for(Scene scene:library.scenes){if(Thread.currentThread().isInterrupted())break;
                world.prefs.edit().putString("world_status","Place information "+(++checked)+"/"+library.scenes.size()+" • "+scene.title).apply();
                try{world.refresh(scene);if(world.info(scene).has("extractAr"))done++;}catch(Exception ignored){}
            }}finally{world.prefs.edit().putBoolean("refreshing",false).apply();}
            final int count=done;runOnUiThread(()->message(count+" places have saved facts. Unmatched places remain unchanged."));
        });
    }
    private void ambientMix(){
        String[] mixes={"All saved pictures","Favourite destinations and photos","Places in daylight now","Places at night now"};
        new AlertDialog.Builder(this).setTitle("Ambient mix").setSingleChoiceItems(mixes,world.prefs.getInt("mix",0),(d,w)->{
            world.prefs.edit().putInt("mix",w).apply();d.dismiss();launchAmbient(null,0);
        }).setNegativeButton("Cancel",null).show();
    }
    private void slideshowSettings(){
        String[] items={"Slide duration","Fit whole picture / Fill screen","Shuffle: "+(world.prefs.getBoolean("shuffle",true)?"On":"Off")};
        new AlertDialog.Builder(this).setTitle("Slideshow settings").setItems(items,(d,w)->{
            if(w==0){int[] seconds={8,12,20,30,60};new AlertDialog.Builder(this).setTitle("Seconds per picture").setItems(new String[]{"8","12","20","30","60"},(a,b)->world.prefs.edit().putInt("seconds",seconds[b]).apply()).show();}
            if(w==1)new AlertDialog.Builder(this).setTitle("Picture framing").setSingleChoiceItems(new String[]{"Fit whole picture (no cropping)","Fill screen (gentle motion)"},world.prefs.getBoolean("fit",false)?0:1,(a,b)->{world.prefs.edit().putBoolean("fit",b==0).apply();a.dismiss();}).show();
            if(w==2){world.prefs.edit().putBoolean("shuffle",!world.prefs.getBoolean("shuffle",true)).apply();slideshowSettings();}
        }).setNegativeButton("Close",null).show();
    }
    private void downloadCenter(){
        stats.execute(()->{
            GalleryStore.Summary summary=GalleryStore.summarize(this,library.scenes);
            StringBuilder details=new StringBuilder("Videos/media: "+library.offlineCount()+"/"+library.scenes.size()+" saved\nPictures: "+summary.photos+" • "+summary.packs+" ready packs\nMedia free space: "+library.human(library.mediaDir().getUsableSpace())+"\nPictures free space: "+library.human(getFilesDir().getUsableSpace())+"\n\n");
            for(Scene s:library.scenes){int n=summary.count(s);details.append(s.arabic.isEmpty()?s.title:s.arabic).append(": ").append(n).append(n>0?" صورة • جاهز\n":" • لم تُحفظ صور\n");}
            runOnUiThread(()->{if(isDestroyed())return;
                new AlertDialog.Builder(this).setTitle("Downloads and storage")
                    .setMessage(details.toString()+"\n"+WorldStore.pictureStatus(getSharedPreferences("infinity_pictures",MODE_PRIVATE).getString("status",""))+"\n"+getSharedPreferences("infinity_downloads",MODE_PRIVATE).getString("status",""))
                    .setPositiveButton(OfflineDownloadService.isRunning()?"Pause":"حفظ / استكمال حسب الإعداد",(d,w)->{
                        if(OfflineDownloadService.isRunning())startService(new Intent(this,OfflineDownloadService.class).setAction(OfflineDownloadService.PAUSE));else downloadPictures(null);
                    }).setNeutralButton("Resume videos",(d,w)->{if(!OfflineDownloadService.isRunning())downloadAllOrPause();else message("Pause the active download first");}).setNegativeButton("Close",null).show();
            });
        });
    }
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
                    surface.refreshScene();
                    scheduleSummary();
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
        super.onResume();
        resumed=true;
        videoProgress.registerOnSharedPreferenceChangeListener(progressListener);
        pictureProgress.registerOnSharedPreferenceChangeListener(progressListener);
        IntentFilter packs=new IntentFilter(GalleryStore.PACK_CHANGED);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(packChanges,packs,Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(packChanges,packs);
        surface.resumeUpdates();
        updateProgress();
        scheduleSummary();
        surface.requestFocus();
    }
    @Override protected void onPause(){
        resumed=false;
        surface.pauseUpdates();
        videoProgress.unregisterOnSharedPreferenceChangeListener(progressListener);
        pictureProgress.unregisterOnSharedPreferenceChangeListener(progressListener);
        unregisterReceiver(packChanges);
        handler.removeCallbacks(progressChanged);
        handler.removeCallbacks(summaryChanged);
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
        stats.shutdownNow();
        worldWorker.shutdownNow();
        placeSearch.shutdownNow();
        world.prefs.edit().putBoolean("refreshing",false).apply();
        if(surface!=null)surface.close();
        super.onDestroy();
    }
}
