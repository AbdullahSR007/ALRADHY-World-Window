package com.alradhy.worldwindow.infinity;

import android.content.Context;
import android.os.Environment;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Small atomic metadata index, independent of large media files. */
public final class LibraryStore {
    private static volatile LibraryStore instance;
    public static LibraryStore shared(Context context) {
        if(instance==null) synchronized(LibraryStore.class) {
            if(instance==null)instance=new LibraryStore(context);
        }
        return instance;
    }
    private final Context app;
    private final File index;
    public final List<Scene> scenes;
    public LibraryStore(Context context) {
        app=context.getApplicationContext();
        index=new File(app.getFilesDir(),"infinity-library-v1.json");
        scenes=new java.util.concurrent.CopyOnWriteArrayList<>(SceneCatalog.create());
        scenes.addAll(new WorldStore(app).custom());
        restore();
    }
    public Context context(){return app;}
    public Scene byId(String id){
        for(Scene s:scenes)if(s.id.equals(id))return s;
        return null;
    }
    public List<Scene> category(String category){
        if(category.equals("MY WORLD")){
            ArrayList<Scene> favourites=new ArrayList<>();
            WorldStore world=new WorldStore(app);
            for(Scene s:scenes)if(world.favourite(s)||world.hasFavouritePhotos(s)||s.id.startsWith("U"))favourites.add(s);
            return favourites;
        }
        if(category.equals("ALL"))return new ArrayList<>(scenes);
        ArrayList<Scene> out=new ArrayList<>();
        for(Scene s:scenes)if(s.category.equals(category))out.add(s);
        return out;
    }
    public synchronized void restore(){
        if(!index.isFile())return;
        try {
            byte[] data=new byte[(int)Math.min(index.length(),2000000)];
            try(FileInputStream in=new FileInputStream(index)){
                int off=0,n;
                while(off<data.length && (n=in.read(data,off,data.length-off))>0)off+=n;
                JSONObject root=new JSONObject(new String(data,0,off,StandardCharsets.UTF_8));
                for(Scene s:scenes)s.restore(root.optJSONObject(s.id));
            }
        }catch(Exception ignored){/* damaged index: static catalog still available */}
    }
    public synchronized void save(){
        JSONObject root=new JSONObject();
        try{
            for(Scene s:scenes)root.put(s.id,s.saved());
            File tmp=new File(index.getAbsolutePath()+".tmp");
            try(FileOutputStream out=new FileOutputStream(tmp)){
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if(!tmp.renameTo(index)){
                try(FileInputStream in=new FileInputStream(tmp);
                    FileOutputStream out=new FileOutputStream(index)){
                    byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);
                    out.getFD().sync();
                }
                tmp.delete();
            }
        }catch(Exception ignored){/* download files are unaffected */}
    }
    public File mediaDir(){
        File external=app.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        File root= external!=null ? external : new File(app.getFilesDir(),"movies");
        File dir=new File(root,"INFINITY");
        if(!dir.exists())dir.mkdirs();
        return dir;
    }
    public File posterDir(){
        File dir=new File(app.getCacheDir(),"posters");
        if(!dir.exists())dir.mkdirs();
        return dir;
    }
    /** Only deletes files inside our own app-scoped media/poster directories. */
    public synchronized boolean deleteOffline(Scene scene){
        if(scene==null||scene.downloading)return false;
        try{
            File movies=mediaDir().getCanonicalFile();
            File images=posterDir().getCanonicalFile();
            if(!scene.localMedia.isEmpty()){
                File media=new File(scene.localMedia).getCanonicalFile();
                if(media.getParentFile().equals(movies)&&media.exists()&&!media.delete())return false;
            }
            if(!scene.localPoster.isEmpty()){
                File poster=new File(scene.localPoster).getCanonicalFile();
                if(poster.getParentFile().equals(images))poster.delete();
            }
            scene.localMedia="";scene.localPoster="";scene.bytes=0;scene.total=0;
            scene.offlineCredit="";scene.offlineLicense="";
            save();return true;
        }catch(Exception e){return false;}
    }
    public int offlineCount(){int n=0;for(Scene s:scenes)if(s.isOffline())n++;return n;}
    public long offlineBytes(){
        long n=0;for(Scene s:scenes)if(s.isOffline())n+=new File(s.localMedia).length();
        return n;
    }
    public String human(long b){
        if(b<1024)return b+" B";
        if(b<1048576)return String.format(java.util.Locale.US,"%.1f KB",b/1024.0);
        if(b<1073741824L)return String.format(java.util.Locale.US,"%.1f MB",b/1048576.0);
        return String.format(java.util.Locale.US,"%.2f GB",b/1073741824.0);
    }
}
