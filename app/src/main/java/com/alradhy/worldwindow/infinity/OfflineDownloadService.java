package com.alradhy.worldwindow.infinity;

import android.app.*;
import android.content.*;
import android.os.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import android.util.Log;
import android.util.AtomicFile;

/**
 * A foreground, resumable queue. App switching does not interrupt an active download.
 * The .part file is only renamed after a full HTTP transfer; interrupted transfers resume.
 */
public final class OfflineDownloadService extends Service {
    public static final String ALL="com.alradhy.infinity.DOWNLOAD_ALL";
    public static final String ONE="com.alradhy.infinity.DOWNLOAD_ONE";
    public static final String PICTURES_ALL="com.alradhy.infinity.PICTURES_ALL";
    public static final String PICTURES_ONE="com.alradhy.infinity.PICTURES_ONE";
    public static final String PAUSE="com.alradhy.infinity.PAUSE";
    public static final String EXTRA_ID="scene_id";
    private static final String CHANNEL="infinity_offline_v1";
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private static volatile String runningAction;
    static boolean isRunning(){return runningAction!=null;}
    static boolean isSavingPictures(){return PICTURES_ALL.equals(runningAction)||PICTURES_ONE.equals(runningAction);}
    private boolean pictures;
    private boolean destroyed;
    private int latestStartId;
    private volatile boolean paused=false;
    private volatile int finished=0,total=0,failures=0;
    private PowerManager.WakeLock wake;
    private LibraryStore library;
    private MediaResolver resolver;

    @Override public void onCreate(){
        super.onCreate();
        library=LibraryStore.shared(this);resolver=new MediaResolver(library);
        NotificationManager nm=getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,"INFINITY offline library",NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        latestStartId=startId;
        String action=intent!=null?intent.getAction():ALL;
        if(PAUSE.equals(action)){
            if(isRunning()){paused=true;notice("Pausing safely…",finished,total);}
            else stopSelf(startId);
            return START_NOT_STICKY;
        }
        if(isRunning())return START_REDELIVER_INTENT;
        runningAction=action==null?ALL:action;
        pictures=isSavingPictures();
        paused=false;finished=0;total=0;failures=0;
        String preparing=pictures?"Preparing offline pictures…":"Preparing offline library…";
        startForeground(6401,notification(preparing,0,0));
        notice(preparing,0,0);
        final String requested=(ONE.equals(action)||PICTURES_ONE.equals(action))&&intent!=null
            ?intent.getStringExtra(EXTRA_ID):null;
        worker.execute(()->process(requested));
        return START_REDELIVER_INTENT;
    }
    private void process(String requested){
        try{
            PowerManager manager=(PowerManager)getSystemService(POWER_SERVICE);
            wake=manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"INFINITY:OfflineQueue");
            wake.acquire(6*60*60*1000L);
            List<Scene> jobs=new ArrayList<>();
            if(requested!=null){Scene one=library.byId(requested);if(one!=null)jobs.add(one);}
            else jobs.addAll(library.scenes);
            total=jobs.size();
            if(pictures){
                processPictures(jobs);
                return;
            }
            Map<Scene,SaveResult> retry=new LinkedHashMap<>();
            int processed=0;
            for(Scene s:jobs){
                if(paused)break;
                processed++;
                if(s.isOffline()){finished++;continue;}
                SaveResult result=saveScene(s,"Checking "+processed+"/"+total+" • ");
                if(result==SaveResult.SAVED){
                    finished++;
                }else if(!paused){
                    retry.put(s,result);
                }
                if(!paused)notice("Saved "+finished+" / "+total+" • "+retry.size()+" to retry",finished,total);
            }
            if(!retry.isEmpty() && !paused){
                notice("Preparing to retry "+retry.size()+" destinations",finished,total);
                for(int i=0;i<20 && !paused;i++)Thread.sleep(250);
                for(Map.Entry<Scene,SaveResult> entry:retry.entrySet()){
                    if(paused)break;
                    Scene s=entry.getKey();
                    boolean saved=entry.getValue()==SaveResult.RETRY
                        && saveScene(s,"Retrying • ")==SaveResult.SAVED;
                    if(saved || (!paused && saveFallback(s)))finished++;
                    else if(!paused)failures++;
                    if(!paused)notice("Ready "+finished+"/"+total+" • "+failures+" unavailable",finished,total);
                }
            }
            if(paused)notice("Paused • completed files are saved",finished,total);
            else {
                long photos=jobs.stream().filter(Scene::hasOfflinePicture).count();
                notice(finished+"/"+total+" ready • "+photos+" as pictures • "+failures+" unavailable",finished,total);
            }
            library.save();
        }catch(StorageFullException | GalleryStore.StorageFullException e){
            notice("Storage low • free space, then resume",finished,total);
        }catch(Exception e){
            notice("Stopped: "+e.getMessage(),finished,total);
        }finally{
            if(wake!=null&&wake.isHeld())wake.release();
            main.post(()->{
                if(destroyed)return;
                runningAction=null;
                getSharedPreferences(progressPreferences(),MODE_PRIVATE).edit().putBoolean("active",false).apply();
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf(latestStartId);
            });
        }
    }
    private void processPictures(List<Scene> scenes)throws GalleryStore.StorageFullException{
        for(int i=0;i<scenes.size()&&!paused;i++){
            Scene scene=scenes.get(i);
            GalleryStore gallery=new GalleryStore(this,scene);
            int position=i+1;
            try{
                List<GalleryStore.Photo> photos=gallery.saved();
                if(photos.size()<GalleryStore.TARGET){
                    notice("Pictures "+position+"/"+total+" • "+scene.title,finished,total);
                    photos=gallery.download(scene,()->paused,(message,items)->{
                        if(!paused)notice(position+"/"+total+" • "+scene.title+" • "+
                            items.size()+"/"+GalleryStore.TARGET+" pictures",finished,total);
                    });
                }
                if(photos.size()>=GalleryStore.TARGET)finished++;
                else if(!paused)failures++;
            }catch(GalleryStore.StorageFullException error){
                throw error;
            }catch(IOException error){
                if(!paused)failures++;
                Log.w("InfinityDownload",scene.id+": picture pack incomplete",error);
            }
        }
        notice(paused?"Pictures paused • saved photos kept":
            "Pictures • "+finished+"/"+total+" packs complete • "+failures+" incomplete",finished,total);
    }
    private String progressPreferences(){return pictures?"infinity_pictures":"infinity_downloads";}
    private enum SaveResult { SAVED, RETRY, UNAVAILABLE }

    private boolean saveFallback(Scene scene)throws StorageFullException{
        if(scene.isOffline())return true;
        scene.downloading=true;
        notice("Finding an offline picture • "+scene.title,finished,total);
        try{
            File parent=library.mediaDir();
            if(parent.getUsableSpace()<80L*1024*1024)throw new StorageFullException();
            GalleryStore.OfflinePicture picture=new GalleryStore(this,scene).fallback(scene,()->paused);
            if(picture==null || paused)return false;
            File path=new File(parent,scene.id+"-offline-photo.img");
            AtomicFile file=new AtomicFile(path);
            FileOutputStream out=file.startWrite();
            try{out.write(picture.bytes);file.finishWrite(out);}
            catch(IOException error){file.failWrite(out);throw error;}
            if(path.length()!=picture.bytes.length)throw new IOException("Picture save incomplete");
            scene.bytes=scene.total=path.length();
            scene.offlineCredit=picture.credit;
            scene.offlineLicense=picture.license;
            scene.localMedia=path.getAbsolutePath();
            scene.error="";
            return true;
        }catch(StorageFullException error){
            throw error;
        }catch(IOException error){
            Log.w("InfinityDownload",scene.id+": could not save fallback picture");
            return false;
        }finally{
            scene.downloading=false;
            library.save();
        }
    }

    private SaveResult saveScene(Scene scene,String progress)throws StorageFullException{
        if(scene.isOffline())return SaveResult.SAVED;
        scene.downloading=true;
        notice(progress+scene.title,finished,total);
        try{
            if(!resolver.resolve(scene,()->paused))throw new IOException(scene.error);
            if(paused)return SaveResult.RETRY;
            String ext=extension(scene);
            File target=new File(library.mediaDir(),scene.id+ext);
            File partial=new File(library.mediaDir(),scene.id+ext+".part");
            if(!download(scene.mediaUrl,partial,target,scene))return SaveResult.RETRY;
            if(!target.isFile() || target.length()!=scene.total)
                throw new IOException("Download did not finalize a complete file");
            scene.localMedia=target.getAbsolutePath();
            scene.error="";
            return SaveResult.SAVED;
        }catch(StorageFullException error){
            throw error;
        }catch(StaleMediaException error){
            resolver.invalidateSource(scene);
            scene.error="Media link expired; source needs a refresh";
            return SaveResult.RETRY;
        }catch(PermanentMediaException error){
            scene.error=error.getMessage();
            Log.w("InfinityDownload",scene.id+": "+scene.error);
            return SaveResult.UNAVAILABLE;
        }catch(Exception error){
            if(!paused){
                scene.error="Download failed: "+error.getMessage();
                Log.w("InfinityDownload",scene.id+": "+scene.error);
            }
            return SaveResult.RETRY;
        }finally{
            scene.downloading=false;
            library.save();
        }
    }

    private static final class StaleMediaException extends IOException {
        StaleMediaException(int status){super("HTTP "+status);}
    }
    private static final class PermanentMediaException extends IOException {
        PermanentMediaException(String message){super(message);}
    }
    private static final class StorageFullException extends IOException {}

    private String extension(Scene s){
        if(s.mime.contains("webm"))return ".webm";
        if(s.mime.contains("ogg"))return ".ogv";
        if(s.mime.startsWith("video/"))return ".mp4";
        if(s.mime.contains("png"))return ".png";
        if(s.mime.contains("webp"))return ".webp";
        return ".jpg";
    }
    private boolean download(String link,File partial,File completed,Scene scene)throws Exception{
        if(!link.startsWith("https://"))throw new IOException("HTTPS required");
        // Resume only when the server can identify the same representation.
        File origin=new File(partial.getAbsolutePath()+".source");
        String validator=null;
        if(partial.isFile() && partial.length()>0){
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(
                    new FileInputStream(origin),java.nio.charset.StandardCharsets.UTF_8))){
                if(link.equals(reader.readLine()))validator=strongEtag(reader.readLine());
            }catch(IOException ignored){/* legacy/unidentified partial: reset safely */}
        }
        boolean resetRange=false;
        while(!paused){
            HttpURLConnection conn=null;
            try{
                long offset=partial.exists()?partial.length():0L;
                if(validator==null)offset=0;
                conn=(HttpURLConnection)new URL(link).openConnection();
                conn.setConnectTimeout(8000);conn.setReadTimeout(9000);
                conn.setRequestProperty("User-Agent","ALRADHY-Infinity/1.0 (personal TV offline client)");
                conn.setRequestProperty("Accept-Encoding","identity");
                if(offset>0){
                    conn.setRequestProperty("Range","bytes="+offset+"-");
                    conn.setRequestProperty("If-Range",validator);
                }
                int code=conn.getResponseCode();
                if(code==416 && offset>0){
                    if(!partial.delete())throw new IOException("Cannot reset rejected range");
                    validator=null;
                    resetRange=true;
                    continue;
                }
                if(code==403 || code==404 || code==410)throw new StaleMediaException(code);
                if(code>=400 && code<500 && code!=408 && code!=429)
                    throw new PermanentMediaException("Source returned HTTP "+code);
                if(code!=200 && code!=206)throw new IOException("HTTP "+code);
                String encoding=conn.getContentEncoding();
                if(encoding!=null && !"identity".equalsIgnoreCase(encoding))
                    throw new PermanentMediaException("Unexpected media content encoding");
                // HTTP 206 must start exactly where our local .part ends.
                // Otherwise an apparently successful download could be corrupt.
                long responseLength=conn.getContentLengthLong();
                String responseTag=strongEtag(conn.getHeaderField("ETag"));
                long expected=code==206
                    ? finalRangeSize(conn.getHeaderField("Content-Range"),offset,responseLength)
                    : responseLength;
                if(code==206 && (expected<0 || offset==0 || !validator.equals(responseTag))){
                    if(offset==0 || resetRange)throw new PermanentMediaException("Invalid media byte range");
                    if(partial.exists() && !partial.delete())throw new IOException("Cannot reset invalid range");
                    validator=null;
                    resetRange=true;
                    continue;
                }
                String contentType=conn.getContentType();
                if(contentType!=null){
                    String type=contentType.toLowerCase(java.util.Locale.ROOT);
                    if(type.startsWith("text/")||type.contains("json")||type.contains("xml"))
                        throw new PermanentMediaException("Unexpected media response type: "+type);
                }
                boolean append=offset>0 && code==206;
                if(!append)offset=0;
                if(expected<=0)throw new PermanentMediaException("Unknown or empty media length");
                long remaining=expected-offset;
                if(remaining>0){
                    StatFs stat=new StatFs(library.mediaDir().getAbsolutePath());
                    if(stat.getAvailableBytes()-80L*1024*1024<remaining)
                        throw new StorageFullException();
                }
                scene.total=expected;
                scene.bytes=offset;
                notice((offset>0?"Resuming • ":"Downloading • ")+scene.title,finished,total);
                try(InputStream in=new BufferedInputStream(conn.getInputStream(),65536);
                    RandomAccessFile out=new RandomAccessFile(partial,"rw")){
                    if(!append){
                        out.setLength(0);
                        out.getFD().sync();
                        validator=null;
                        try(FileOutputStream metadata=new FileOutputStream(origin)){
                            if(responseTag!=null){
                                metadata.write((link+"\n"+responseTag).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            }
                            metadata.getFD().sync();
                        }
                        validator=responseTag;
                    }
                    out.seek(offset);
                    byte[] buf=new byte[131072];int n;long done=offset,last=SystemClock.elapsedRealtime();
                    long windowStart=last,windowBytes=offset;
                    while(!paused && (n=in.read(buf))!=-1){
                        if(paused)break;
                        if(n>expected-done)throw new IOException("Media exceeds declared size");
                        out.write(buf,0,n);done+=n;scene.bytes=done;
                        long now=SystemClock.elapsedRealtime();
                        long windowMillis=now-windowStart;
                        if(windowMillis>=20_000 && done<expected){
                            if(transferTooSlow(done-windowBytes,windowMillis))
                                throw new SocketTimeoutException("Source too slow; partial file kept");
                            windowStart=now;
                            windowBytes=done;
                        }
                        if(now-last>1400){
                            last=now;
                            notice(library.human(done)+" / "+library.human(expected)+" • "+scene.title,finished,total);
                        }
                    }
                    if(paused)return false;
                    out.getFD().sync();
                    if(done<=0)throw new EOFException("Empty media response");
                    if(expected>0 && done!=expected)throw new EOFException("Incomplete "+done+"/"+expected);
                }
                java.nio.file.Files.move(partial.toPath(),completed.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                origin.delete();
                return true;
            }finally{if(conn!=null)conn.disconnect();}
        }
        return false;
    }
    static boolean transferTooSlow(long bytes,long elapsedMillis){
        return elapsedMillis>=20_000 && bytes*1000.0/elapsedMillis<16*1024;
    }
    private static String strongEtag(String value){
        return value!=null && value.length()>=2 && value.startsWith("\"") &&
            value.endsWith("\"") && value.indexOf('"',1)==value.length()-1 ? value : null;
    }

    /** This client requests the entire remaining tail, with a known total size. */
    private static long finalRangeSize(String header,long start,long bodyLength){
        if(header==null || !header.startsWith("bytes "))return -1;
        int dash=header.indexOf('-',6);
        int slash=header.indexOf('/',dash+1);
        if(dash<7 || slash<=dash+1)return -1;
        try{
            long first=Long.parseLong(header.substring(6,dash));
            long last=Long.parseLong(header.substring(dash+1,slash));
            long size=Long.parseLong(header.substring(slash+1).trim());
            if(first!=start || last<first || size<=last || last!=size-1)return -1;
            return bodyLength<0 || bodyLength==size-first ? size : -1;
        }catch(NumberFormatException e){return -1;}
    }

    private Notification notification(String message,int count,int max){
        Intent open=new Intent(this,MainActivity.class);
        PendingIntent pi=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b=new Notification.Builder(this,CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("ALRADHY INFINITY • Offline library")
            .setContentText(message).setContentIntent(pi).setOngoing(count<max)
            .setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_PROGRESS);
        if(max>0)b.setProgress(max,count,false);
        else b.setProgress(0,0,true);
        return b.build();
    }
    private void notice(String status,int count,int max){
        getSharedPreferences(progressPreferences(),MODE_PRIVATE).edit()
            .putString("status",status).putInt("done",count)
            .putInt("total",max).putInt("errors",failures)
            .putBoolean("active",isRunning()).apply();
        getSystemService(NotificationManager.class).notify(6401,notification(status,count,max));
    }
    @Override public void onDestroy(){destroyed=true;paused=true;runningAction=null;worker.shutdownNow();super.onDestroy();}
    @Override public android.os.IBinder onBind(Intent intent){return null;}
}
