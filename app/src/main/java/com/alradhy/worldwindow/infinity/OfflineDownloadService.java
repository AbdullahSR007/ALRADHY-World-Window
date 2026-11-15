package com.alradhy.worldwindow.infinity;

import android.app.*;
import android.content.*;
import android.os.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A foreground, resumable queue. App switching does not interrupt an active download.
 * The .part file is only renamed after a full HTTP transfer; interrupted transfers resume.
 */
public final class OfflineDownloadService extends Service {
    public static final String ALL="com.alradhy.infinity.DOWNLOAD_ALL";
    public static final String ONE="com.alradhy.infinity.DOWNLOAD_ONE";
    public static final String PAUSE="com.alradhy.infinity.PAUSE";
    public static final String EXTRA_ID="scene_id";
    private static final String CHANNEL="infinity_offline_v1";
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final AtomicBoolean active=new AtomicBoolean(false);
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
        String action=intent!=null?intent.getAction():ALL;
        if(PAUSE.equals(action)){paused=true;notice("Pausing safely…",0,0);return START_NOT_STICKY;}
        if(!active.compareAndSet(false,true))return START_NOT_STICKY;
        paused=false;finished=0;failures=0;
        startForeground(6401,notification("Preparing offline library…",0,0));
        final String requested=ONE.equals(action)&&intent!=null?intent.getStringExtra(EXTRA_ID):null;
        worker.execute(()->process(requested,startId));
        return START_NOT_STICKY;
    }
    private void process(String requested,int startId){
        try{
            PowerManager manager=(PowerManager)getSystemService(POWER_SERVICE);
            wake=manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"INFINITY:OfflineQueue");
            wake.acquire(6*60*60*1000L);
            List<Scene> jobs=new ArrayList<>();
            if(requested!=null){Scene one=library.byId(requested);if(one!=null)jobs.add(one);}
            else jobs.addAll(library.scenes);
            total=jobs.size();
            for(Scene s:jobs){
                if(paused)break;
                if(s.isOffline()){finished++;continue;}
                notice("Finding "+s.title,finished,total);
                try {
                    if(!resolver.resolve(s))throw new IOException(s.error);
                    String ext=extension(s);
                    File target=new File(library.mediaDir(),s.id+ext);
                    File tmp=new File(library.mediaDir(),s.id+ext+".part");
                    s.downloading=true;
                    try {
                        download(s.mediaUrl,tmp,target,s);
                        if(paused)break;
                        s.localMedia=target.getAbsolutePath();
                        s.error="";
                        cachePoster(s);
                        library.save();
                    }finally{s.downloading=false;}
                }catch(Exception error){
                    s.error="Download failed: "+error.getMessage();
                    failures++;library.save();
                }
                finished++;
                notice("Saved "+finished+" / "+total+" • "+failures+" unavailable",finished,total);
            }
            if(paused)notice("Paused • completed files are saved",finished,total);
            else notice("Finished • "+library.offlineCount()+" saved, "+failures+" unavailable",total,total);
            library.save();
        }catch(Exception e){
            notice("Stopped: "+e.getMessage(),finished,total);
        }finally{
            if(wake!=null&&wake.isHeld())wake.release();
            active.set(false);
            stopForeground(STOP_FOREGROUND_DETACH);
            stopSelf(startId);
        }
    }
    private String extension(Scene s){
        if(s.mime.contains("webm"))return ".webm";
        if(s.mime.contains("ogg"))return ".ogv";
        if(s.mime.startsWith("video/"))return ".mp4";
        if(s.mime.contains("png"))return ".png";
        if(s.mime.contains("webp"))return ".webp";
        return ".jpg";
    }
    private void download(String link,File partial,File completed,Scene scene)throws Exception{
        if(!link.startsWith("https://"))throw new IOException("HTTPS required");
        if(completed.exists()&&completed.length()>0)return;
        int retries=0;
        while(!paused && retries<3){
            HttpURLConnection conn=null;
            try{
                long offset=partial.exists()?partial.length():0L;
                conn=(HttpURLConnection)new URL(link).openConnection();
                conn.setConnectTimeout(20000);conn.setReadTimeout(30000);
                conn.setRequestProperty("User-Agent","ALRADHY-Infinity/1.0 (personal TV offline client)");
                conn.setRequestProperty("Accept-Encoding","identity");
                if(offset>0)conn.setRequestProperty("Range","bytes="+offset+"-");
                int code=conn.getResponseCode();
                if(code==416 && offset>0){partial.delete();throw new IOException("Invalid old partial; restarting");}
                if(code!=200 && code!=206)throw new IOException("HTTP "+code);
                boolean append=offset>0 && code==206;
                if(!append)offset=0;
                long remaining=conn.getContentLengthLong();
                long expected=remaining>0?remaining+offset:0;
                if(remaining>0){
                    StatFs stat=new StatFs(library.mediaDir().getAbsolutePath());
                    if(stat.getAvailableBytes()<remaining+80L*1024*1024)
                        throw new IOException("Insufficient free space; free storage and resume");
                }
                scene.total=expected;
                try(InputStream in=new BufferedInputStream(conn.getInputStream(),65536);
                    RandomAccessFile out=new RandomAccessFile(partial,"rw")){
                    if(!append)out.setLength(0);
                    out.seek(offset);
                    byte[] buf=new byte[131072];int n;long done=offset,last=SystemClock.elapsedRealtime();
                    while(!paused && (n=in.read(buf))!=-1){
                        out.write(buf,0,n);done+=n;scene.bytes=done;
                        long now=SystemClock.elapsedRealtime();
                        if(now-last>1400){
                            last=now;notice(scene.title+" • "+library.human(done)+
                                    (expected>0?" / "+library.human(expected):""),finished,total);
                        }
                    }
                    if(paused)return;
                    out.getFD().sync();
                    if(expected>0 && done!=expected)throw new EOFException("Incomplete "+done+"/"+expected);
                }
                if(completed.exists()&&!completed.delete())throw new IOException("Cannot replace file");
                if(!partial.renameTo(completed))throw new IOException("Cannot finalize file");
                return;
            }catch(IOException e){
                retries++;
                if(retries>=3)throw e;
                try{Thread.sleep(1200L*retries);}catch(InterruptedException interrupted){
                    Thread.currentThread().interrupt();return;
                }
            }finally{if(conn!=null)conn.disconnect();}
        }
    }
    private void cachePoster(Scene s){
        if(s.posterUrl.isEmpty()||paused)return;
        File target=new File(library.posterDir(),s.id+".jpg");
        if(target.isFile()&&target.length()>0){s.localPoster=target.getAbsolutePath();return;}
        HttpURLConnection connection=null;
        try{
            connection=(HttpURLConnection)new URL(s.posterUrl).openConnection();
            connection.setConnectTimeout(12000);connection.setReadTimeout(18000);
            if(connection.getResponseCode()!=200)return;
            try(InputStream in=connection.getInputStream();FileOutputStream out=new FileOutputStream(target)){
                byte[] bytes=new byte[32768];int n;long count=0;
                while((n=in.read(bytes))!=-1 && !paused){
                    count+=n;if(count>12_000_000)throw new IOException("Poster too large");
                    out.write(bytes,0,n);
                }
            }
            if(target.isFile()&&target.length()>0&&!paused)s.localPoster=target.getAbsolutePath();
            else target.delete();
        }catch(Exception ignored){target.delete();}
        finally{if(connection!=null)connection.disconnect();}
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
        getSharedPreferences("infinity_downloads",MODE_PRIVATE).edit()
            .putString("status",status).putInt("done",count)
            .putInt("total",max).putInt("errors",failures)
            .putBoolean("active",active.get()&&!paused).apply();
        getSystemService(NotificationManager.class).notify(6401,notification(status,count,max));
    }
    @Override public void onDestroy(){paused=true;worker.shutdownNow();super.onDestroy();}
    @Override public android.os.IBinder onBind(Intent intent){return null;}
}
