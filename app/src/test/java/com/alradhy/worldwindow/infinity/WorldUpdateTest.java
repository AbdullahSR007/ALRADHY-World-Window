package com.alradhy.worldwindow.infinity;

import android.content.Context;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.json.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.time.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,manifest=Config.NONE)
public class WorldUpdateTest {
    Context app;
    @Before public void setup()throws Exception{
        app=RuntimeEnvironment.getApplication();
        java.lang.reflect.Field singleton=LibraryStore.class.getDeclaredField("instance");
        singleton.setAccessible(true);singleton.set(null,null);
    }
    void write(File file,byte[] bytes)throws Exception{file.getParentFile().mkdirs();Files.write(file.toPath(),bytes);}
    void seedPack(Scene scene,int count)throws Exception{
        File dir=new File(app.getFilesDir(),"pictures/"+scene.id);
        JSONArray index=new JSONArray();
        for(int i=0;i<count;i++){
            String name=String.format(Locale.US,"%064x.img",i+1);
            write(new File(dir,name),(scene.id+" photo "+i).getBytes(StandardCharsets.UTF_8));
            index.put(new JSONObject().put("file",name).put("title",scene.title).put("license","Test fixture"));
        }
        write(new File(dir,"index.json"),index.toString().getBytes(StandardCharsets.UTF_8));
    }
    @Test public void oversizedProgressiveImageIsOutsideDecodeBudget(){
        assertFalse(GalleryStore.safeImageDimensions(18756,8588));
        assertFalse(GalleryStore.safeImageDimensions(65535,65535));
        assertFalse(GalleryStore.safeImageDimensions(0,1080));
        assertTrue(GalleryStore.safeImageDimensions(1920,1080));
    }

    @Test public void headerFocusWaitsForOkAndPageDownJumpsFiveCards(){
        LibraryStore library=LibraryStore.shared(app);
        for(Scene scene:library.scenes)scene.mediaUrl="https://example.invalid/test";
        org.robolectric.android.controller.ActivityController<MainActivity> controller=
            Robolectric.buildActivity(MainActivity.class).create();
        try{
            MainActivity activity=controller.get();
            android.view.ViewGroup content=activity.findViewById(android.R.id.content);
            CinematicView home=(CinematicView)content.getChildAt(0);
            home.restoreSelection("ALL","C1");
            press(home,android.view.KeyEvent.KEYCODE_DPAD_UP);
            press(home,android.view.KeyEvent.KEYCODE_DPAD_UP);
            press(home,android.view.KeyEvent.KEYCODE_DPAD_RIGHT);
            assertEquals("ALL",home.selectedCategory());
            press(home,android.view.KeyEvent.KEYCODE_DPAD_CENTER);
            assertEquals("CITIES",home.selectedCategory());
            press(home,android.view.KeyEvent.KEYCODE_PAGE_DOWN);
            assertEquals("C6",home.selected().id);
        }finally{controller.destroy();}
    }

    private void press(CinematicView view,int key){
        assertTrue(view.onKeyDown(key,new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,key)));
    }

    @Test public void legacy100VideosAnd2000PicturesSurviveAddingPlaceAndReload()throws Exception{
        List<Scene> catalog=SceneCatalog.create();assertEquals(100,catalog.size());
        JSONObject legacy=new JSONObject();Map<File,byte[]> originals=new HashMap<>();
        LibraryStore paths=new LibraryStore(app);
        for(Scene s:catalog){
            File media=new File(paths.mediaDir(),s.id+".mp4");byte[] bytes=("saved video "+s.id).getBytes(StandardCharsets.UTF_8);
            write(media,bytes);originals.put(media,bytes);
            legacy.put(s.id,new JSONObject().put("localMedia",media.getAbsolutePath()).put("total",bytes.length).put("bytes",bytes.length).put("mime","video/mp4").put("credit","existing credit"));
            seedPack(s,20);
            File folder=new File(app.getFilesDir(),"pictures/"+s.id);
            for(File file:folder.listFiles())originals.put(file,Files.readAllBytes(file.toPath()));
        }
        write(new File(app.getFilesDir(),"infinity-library-v1.json"),legacy.toString().getBytes(StandardCharsets.UTF_8));
        app.getSharedPreferences("infinity_browsing",0).edit().putString("scene","C8").commit();
        WorldStore world=new WorldStore(app);
        Scene added=world.add(new JSONObject().put("title","Test landmark").put("pageid",999991));
        world.toggle(catalog.get(0));
        LibraryStore library=new LibraryStore(app);
        assertEquals(101,library.scenes.size());assertNotNull(library.byId(added.id));
        assertEquals(100,library.offlineCount());assertEquals(2000,GalleryStore.summarize(app,library.scenes).photos);
        library.save();LibraryStore reloaded=new LibraryStore(app);
        assertEquals(100,reloaded.offlineCount());assertEquals(100,GalleryStore.summarize(app,reloaded.scenes).packs);
        assertEquals("existing credit",reloaded.byId("C1").credit);
        assertEquals("C8",app.getSharedPreferences("infinity_browsing",0).getString("scene",""));
        for(Map.Entry<File,byte[]> entry:originals.entrySet())assertArrayEquals(entry.getKey().toString(),entry.getValue(),Files.readAllBytes(entry.getKey().toPath()));
    }
    @Test public void slideshowCollectsAllPlacesAndFiltersOnlyWhenRequested()throws Exception{
        LibraryStore library=LibraryStore.shared(app);WorldStore world=new WorldStore(app);
        Scene a=library.scenes.get(0),b=library.scenes.get(1);seedPack(a,2);seedPack(b,3);
        assertEquals(5,AmbientView.collect(app,null).size());
        assertEquals(2,AmbientView.collect(app,a.id).size());
        world.prefs.edit().putInt("mix",1).commit();assertEquals(0,AmbientView.collect(app,null).size());
        world.toggle(a);assertEquals(2,AmbientView.collect(app,null).size());
        world.togglePhoto(b,String.format(Locale.US,"%064x.img",1));assertEquals(3,AmbientView.collect(app,null).size());
        assertTrue(library.category("MY WORLD").contains(b));
        world.prefs.edit().putInt("mix",0).commit();assertEquals(5,AmbientView.collect(app,null).size());
    }
    @Test public void newPlacePersistsAndDuplicateDoesNotReplaceIt()throws Exception{
        WorldStore world=new WorldStore(app);JSONObject place=new JSONObject().put("pageid",777).put("title","New place");
        Scene added=world.add(place);
        try{world.add(place);fail("Duplicate must be rejected");}catch(IllegalArgumentException expected){}
        WorldStore reloaded=new WorldStore(app);assertEquals(1,reloaded.custom().size());
        assertEquals(added.id,reloaded.custom().get(0).id);
        LibraryStore library=new LibraryStore(app);assertEquals(101,library.scenes.size());
        assertTrue(library.category("MY WORLD").stream().anyMatch(s->s.id.equals(added.id)));
    }
    @Test public void expiredSunTimesNeverPretendToBeLive()throws Exception{
        WorldStore world=new WorldStore(app);Scene scene=SceneCatalog.create().get(0);
        String today=LocalDate.now(ZoneOffset.UTC).toString();
        JSONObject daily=new JSONObject().put("time",new JSONArray().put(today))
            .put("sunrise",new JSONArray().put(today+"T00:00")).put("sunset",new JSONArray().put(today+"T23:59"));
        world.prefs.edit().putString("info:"+scene.id,new JSONObject().put("zone","UTC").put("daily",daily).toString()).commit();
        assertTrue(world.daylight(scene).startsWith("Daylight"));
        daily.put("time",new JSONArray().put("2000-01-01"));
        world.prefs.edit().putString("info:"+scene.id,new JSONObject().put("zone","UTC").put("daily",daily).toString()).commit();
        assertEquals("Sun times need refresh",world.daylight(scene));
    }
    @Test public void completePackIsNeverDownloadedAgain()throws Exception{
        Scene scene=SceneCatalog.create().get(0);seedPack(scene,20);
        GalleryStore gallery=new GalleryStore(app,scene);
        assertEquals(20,gallery.download(scene,()->false,(message,photos)->fail("Completed pack should not issue work")).size());
    }
    @Test public void mainSlideshowIgnoresSelectedCityAndClearsMix()throws Exception{
        LibraryStore library=LibraryStore.shared(app);
        for(Scene scene:library.scenes)scene.mediaUrl="https://example.invalid/test";
        WorldStore world=new WorldStore(app);world.prefs.edit().putInt("mix",1).commit();
        org.robolectric.android.controller.ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).create();
        try{
            MainActivity activity=controller.get();activity.ambient(library.scenes.get(0),2);
            android.content.Intent intent=Shadows.shadowOf(activity).getNextStartedActivity();
            assertEquals(AmbientActivity.class.getName(),intent.getComponent().getClassName());
            assertNull(intent.getStringExtra(AmbientActivity.EXTRA_SCENE));
            assertEquals(0,world.prefs.getInt("mix",-1));
        }finally{controller.destroy();}
    }
    @Test public void categoriesPersistAndLegacyPlacesKeepCitiesDefault()throws Exception{
        WorldStore world=new WorldStore(app);
        world.prefs.edit().putString("places",new JSONArray().put(new JSONObject().put("id","U700").put("title","Legacy place")).toString()).commit();
        assertEquals("CITIES",world.custom().get(0).category);
        for(int i=0;i<WorldStore.PLACE_CATEGORIES.length;i++){
            Scene added=world.add(new JSONObject().put("pageid",800+i).put("title","New "+i),WorldStore.PLACE_CATEGORIES[i]);
            assertEquals(WorldStore.PLACE_CATEGORIES[i],added.category);
            assertEquals(i==3?"NASA":"COMMONS",added.provider);
        }
        LibraryStore reloaded=new LibraryStore(app);
        assertEquals("SPACE",reloaded.byId("U803").category);
        assertTrue(reloaded.category("SPACE").contains(reloaded.byId("U803")));
        try{world.add(new JSONObject().put("pageid",900).put("title","Bad"),"ALL");fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void deleteCustomRemovesOnlyItsFilesAndNeverBuiltInPlaces()throws Exception{
        WorldStore world=new WorldStore(app);Scene added=world.add(new JSONObject().put("pageid",999).put("title","Remove me"),"SPACE");
        LibraryStore library=new LibraryStore(app);added=library.byId(added.id);
        Scene original=library.scenes.get(0);seedPack(original,2);seedPack(added,3);
        byte[] keep="keep this video".getBytes(StandardCharsets.UTF_8);
        File preserved=new File(library.mediaDir(),original.id+".mp4");write(preserved,keep);
        File remove=new File(library.mediaDir(),added.id+".mp4");write(remove,new byte[]{1,2,3});added.localMedia=remove.getAbsolutePath();added.total=3;
        File part=new File(library.mediaDir(),added.id+".mp4.part");write(part,new byte[]{1});
        world.toggle(added);world.togglePhoto(added,String.format(Locale.US,"%064x.img",1));library.save();
        library.deleteCustom(added);
        assertNull(library.byId(added.id));assertFalse(remove.exists());assertFalse(part.exists());
        assertEquals(0,new GalleryStore(app,added).saved().size());assertEquals(2,new GalleryStore(app,original).saved().size());
        assertArrayEquals(keep,Files.readAllBytes(preserved.toPath()));assertFalse(world.favourite(added));assertFalse(world.hasFavouritePhotos(added));
        assertEquals(100,new LibraryStore(app).scenes.size());
        try{library.deleteCustom(original);fail();}catch(IllegalArgumentException expected){}
        assertArrayEquals(keep,Files.readAllBytes(preserved.toPath()));
    }
    @Test public void descriptionsAlwaysArabicEvenWithLegacyEnglishCache()throws Exception{
        WorldStore world=new WorldStore(app);
        for(Scene s:SceneCatalog.create()){
            world.prefs.edit().putString("info:"+s.id,new JSONObject().put("extract","English cached description").toString()).commit();
            assertTrue(s.id,world.description(s).matches(".*[\\u0600-\\u06ff].*"));
            assertFalse(world.description(s).contains("English cached"));
            assertNotEquals(WorldStore.NO_ARABIC,world.description(s));
        }
        Scene space=world.add(new JSONObject().put("pageid",123).put("title","Space").put("extractAr","وصف عربي محفوظ"),"SPACE");
        assertEquals("وصف عربي محفوظ",world.description(space));
    }
    @Test public void twoPhotosAreReadyAndManualBulkDoesNotTopUp()throws Exception{
        LibraryStore library=LibraryStore.shared(app);Scene scene=library.scenes.get(0);seedPack(scene,2);
        assertEquals(1,GalleryStore.summarize(app,Collections.singletonList(scene)).packs);
        org.robolectric.android.controller.ServiceController<OfflineDownloadService> controller=Robolectric.buildService(OfflineDownloadService.class).create();
        try{
            OfflineDownloadService service=controller.get();
            java.lang.reflect.Field total=OfflineDownloadService.class.getDeclaredField("total");total.setAccessible(true);total.set(service,1);
            java.lang.reflect.Method process=OfflineDownloadService.class.getDeclaredMethod("processPictures",List.class);process.setAccessible(true);process.invoke(service,Collections.singletonList(scene));
            java.lang.reflect.Field finished=OfflineDownloadService.class.getDeclaredField("finished");finished.setAccessible(true);assertEquals(1,finished.getInt(service));
            assertEquals(2,new GalleryStore(app,scene).saved().size());
        }finally{controller.destroy();}
    }
}
