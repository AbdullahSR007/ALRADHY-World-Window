package com.alradhy.worldwindow.infinity;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Downsampled, bounded-memory image loader; every card retains its unique poster. */
final class PosterLoader {
    private final LruCache<String,Bitmap> cache=new LruCache<String,Bitmap>(28*1024){
        @Override protected int sizeOf(String key,Bitmap bitmap){return bitmap.getByteCount()/1024;}
    };
    private final Set<String> pending=Collections.synchronizedSet(new HashSet<>());
    private final ExecutorService pool=Executors.newFixedThreadPool(2);
    private final LibraryStore library;
    PosterLoader(LibraryStore library){this.library=library;}
    Bitmap peek(Scene s,Runnable repaint){
        String key=s.id+":"+(s.localPoster.isEmpty()?s.posterUrl:s.localPoster);
        Bitmap current=cache.get(key);
        if(current!=null&&!current.isRecycled())return current;
        if(!pending.add(key))return null;
        pool.execute(()->{
            try{
                Bitmap img=decode(s);
                if(img!=null){cache.put(key,img);repaint.run();}
            }catch(Exception ignored){}
            finally{pending.remove(key);}
        });
        return null;
    }
    private Bitmap decode(Scene s)throws Exception{
        String local=s.localPoster;
        if(!local.isEmpty()&&new File(local).isFile())return decodeFile(new File(local));
        if(s.isOffline()&&!s.isVideo())return decodeFile(new File(s.localMedia));
        if(s.posterUrl.isEmpty())return null;
        if(!s.posterUrl.startsWith("https://"))return null;
        File temp=new File(library.posterDir(),s.id+"_preview.jpg");
        if(temp.isFile() && temp.length()>0)return decodeFile(temp);
        HttpURLConnection c=(HttpURLConnection)new URL(s.posterUrl).openConnection();
        c.setConnectTimeout(12000);c.setReadTimeout(18000);
        c.setRequestProperty("User-Agent","ALRADHY-Infinity/1.0 (personal TV client)");
        try{
            if(c.getResponseCode()!=200)return null;
            try(InputStream in=c.getInputStream(); ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] buf=new byte[32768];int n,total=0;
                while((n=in.read(buf))!=-1){
                    total+=n;if(total>15_000_000)return null;
                    out.write(buf,0,n);
                }
                byte[] data=out.toByteArray();
                BitmapFactory.Options options=new BitmapFactory.Options();
                options.inJustDecodeBounds=true;
                BitmapFactory.decodeByteArray(data,0,data.length,options);
                options.inSampleSize=sample(options.outWidth,options.outHeight);
                options.inJustDecodeBounds=false;
                Bitmap b=BitmapFactory.decodeByteArray(data,0,data.length,options);
                if(b!=null){
                    try(FileOutputStream f=new FileOutputStream(temp)){f.write(data);}
                }
                return b;
            }
        }finally{c.disconnect();}
    }
    private Bitmap decodeFile(File file){
        BitmapFactory.Options o=new BitmapFactory.Options();
        o.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getAbsolutePath(),o);
        o.inSampleSize=sample(o.outWidth,o.outHeight);o.inJustDecodeBounds=false;
        return BitmapFactory.decodeFile(file.getAbsolutePath(),o);
    }
    private int sample(int w,int h){
        int s=1;
        while(w/s>1500 || h/s>1000)s*=2;
        return s;
    }
    void shutdown(){pool.shutdownNow();cache.evictAll();}
}
