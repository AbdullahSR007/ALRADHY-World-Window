package com.alradhy.worldwindow.infinity;

import android.graphics.*;
import android.media.ThumbnailUtils;
import android.provider.MediaStore;
import android.util.LruCache;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Downsampled, bounded-memory image loader; every card retains its unique poster. */
final class PosterLoader {
    private final LruCache<String,Bitmap> cache;
    private final Set<String> pending=Collections.synchronizedSet(new HashSet<>());
    private final Map<String,Long> retryAfter=new ConcurrentHashMap<>();
    private final ExecutorService pool=Executors.newFixedThreadPool(2);
    private final LibraryStore library;

    PosterLoader(LibraryStore library){
        this.library=library;
        android.app.ActivityManager memory=library.context().getSystemService(android.app.ActivityManager.class);
        int budget=Math.max(6,Math.min(16,memory.getMemoryClass()/12));
        cache=new LruCache<String,Bitmap>(budget*1024*1024){
            @Override protected int sizeOf(String key,Bitmap bitmap){return bitmap.getAllocationByteCount();}
        };
    }

    Bitmap peek(Scene scene,Runnable repaint){
        return peek(scene,scene.isOffline(),1500,1000,repaint);
    }

    Bitmap peek(Scene scene,boolean offline,int width,int height,Runnable repaint){
        return peek(scene,offline,width,height,false,repaint);
    }

    Bitmap peek(Scene scene,boolean offline,int width,int height,boolean compose,Runnable repaint){
        String localMedia=offline?scene.localMedia:"";
        String localPoster=scene.localPoster;
        String url=scene.posterUrl;
        String key=scene.id+":"+localMedia+":"+localPoster+":"+url+":"+width+":"+compose;
        return request(key,()->{
            Bitmap bitmap=decode(scene,localMedia,localPoster,url,width,height);
            return bitmap==null||!compose?bitmap:decorate(bitmap,width==1120);
        },repaint);
    }

    Bitmap peekPicture(File file,int width,int height,Runnable repaint){
        return request(file.getAbsolutePath()+":"+width,()->decodeFile(file,width,height),repaint);
    }

    private Bitmap decorate(Bitmap source,boolean hero){
        int width=hero?1118:308,height=hero?558:205,radius=hero?25:15;
        Bitmap result=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(result);
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        BitmapShader photo=new BitmapShader(source,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);
        float scale=Math.max(width/(float)source.getWidth(),height/(float)source.getHeight());
        Matrix crop=new Matrix();
        crop.setScale(scale,scale);
        crop.postTranslate((width-source.getWidth()*scale)/2f,(height-source.getHeight()*scale)/2f);
        photo.setLocalMatrix(crop);
        paint.setShader(photo);
        canvas.drawRoundRect(0,0,width,height,radius,radius,paint);
        paint.setShader(hero?new LinearGradient(0,243,0,573,0x00101b24,0xe908171f,Shader.TileMode.CLAMP):
            new LinearGradient(0,80,0,205,0x00101920,0xee081821,Shader.TileMode.CLAMP));
        canvas.drawRoundRect(0,0,width,height,radius,radius,paint);
        if(hero){
            paint.setShader(new LinearGradient(0,0,478,0,0x7704121a,0x00000000,Shader.TileMode.CLAMP));
            canvas.drawRoundRect(0,0,width,height,radius,radius,paint);
        }
        source.recycle();
        return result;
    }

    private Bitmap request(String key,Callable<Bitmap> decode,Runnable repaint){
        Bitmap current=cache.get(key);
        if(current!=null&&!current.isRecycled())return current;
        Long retry=retryAfter.get(key);
        if(retry!=null&&android.os.SystemClock.uptimeMillis()<retry)return null;
        if(!pending.add(key))return null;
        pool.execute(()->{
            try{
                Bitmap img=decode.call();
                if(img!=null){
                    retryAfter.remove(key);
                    cache.put(key,img);
                    repaint.run();
                }else deferRetry(key);
            }catch(Exception ignored){deferRetry(key);}
            finally{pending.remove(key);}
        });
        return null;
    }
    private void deferRetry(String key){
        retryAfter.put(key,android.os.SystemClock.uptimeMillis()+60_000);
    }

    private Bitmap decode(Scene scene,String localMedia,String localPoster,String url,int width,int height)throws Exception{
        boolean offline=!localMedia.isEmpty();
        boolean still=offline&&(localMedia.endsWith("-offline-photo.img")||!scene.mime.startsWith("video/"));
        if(still){
            Bitmap original=decodeFile(new File(localMedia),width,height);
            if(original!=null)return original;
        }
        if(!localPoster.isEmpty()&&new File(localPoster).isFile())
            return decodeFile(new File(localPoster),width,height);
        File cached=new File(library.posterDir(),scene.id+"_preview.jpg");
        if(cached.isFile()){
            Bitmap preview=decodeFile(cached,width,height);
            if(preview!=null)return preview;
        }
        if(still){
            GalleryStore gallery=new GalleryStore(library.context(),scene);
            for(GalleryStore.Photo photo:gallery.saved()){
                Bitmap preview=decodeFile(gallery.file(photo),width,height);
                if(preview!=null)return preview;
            }
        }
        if(offline&&!still){
            Bitmap image=ThumbnailUtils.createVideoThumbnail(localMedia,MediaStore.Video.Thumbnails.MINI_KIND);
            return limit(image,width,height);
        }
        if(!url.startsWith("https://"))return null;
        synchronized(cached.getName().intern()){
            if(cached.isFile()){
                Bitmap preview=decodeFile(cached,width,height);
                if(preview!=null)return preview;
            }
            return download(url,cached,width,height);
        }
    }

    private Bitmap download(String url,File cached,int width,int height)throws Exception{
        HttpURLConnection connection=(HttpURLConnection)new URL(url).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(18000);
        connection.setRequestProperty("User-Agent","ALRADHY-Infinity/1.0 (personal TV client)");
        try{
            if(connection.getResponseCode()!=200)return null;
            File temp=File.createTempFile("poster-",".tmp",cached.getParentFile());
            try{
                try(InputStream input=connection.getInputStream(); OutputStream output=new FileOutputStream(temp)){
                    byte[] buffer=new byte[32768];
                    int count,total=0;
                    while((count=input.read(buffer))!=-1){
                        total+=count;
                        if(total>15_000_000||Thread.currentThread().isInterrupted())return null;
                        output.write(buffer,0,count);
                    }
                }
                Bitmap bitmap=decodeFile(temp,width,height);
                if(bitmap!=null)temp.renameTo(cached);
                return bitmap;
            }finally{temp.delete();}
        }finally{connection.disconnect();}
    }

    private Bitmap decodeFile(File file,int width,int height){
        String path=file.getAbsolutePath();
        BitmapFactory.Options options=new BitmapFactory.Options();
        options.inJustDecodeBounds=true;
        BitmapFactory.decodeFile(path,options);
        if(!GalleryStore.safeImageDimensions(options.outWidth,options.outHeight))return null;
        float scale=Math.min(1f,Math.min(width/(float)options.outWidth,height/(float)options.outHeight));
        int sample=1;
        while(sample*2*scale<=1)sample*=2;
        options.inSampleSize=sample;
        options.inJustDecodeBounds=false;
        options.inPreferredConfig=Bitmap.Config.RGB_565;
        return limit(BitmapFactory.decodeFile(path,options),width,height);
    }

    private Bitmap limit(Bitmap bitmap,int width,int height){
        if(bitmap==null)return null;
        float scale=Math.min(1f,Math.min(width/(float)bitmap.getWidth(),height/(float)bitmap.getHeight()));
        if(scale==1)return bitmap;
        Bitmap scaled=Bitmap.createScaledBitmap(bitmap,Math.max(1,Math.round(bitmap.getWidth()*scale)),
            Math.max(1,Math.round(bitmap.getHeight()*scale)),true);
        if(bitmap!=scaled)bitmap.recycle();
        return scaled;
    }

    void shutdown(){pool.shutdownNow();cache.evictAll();}
}
