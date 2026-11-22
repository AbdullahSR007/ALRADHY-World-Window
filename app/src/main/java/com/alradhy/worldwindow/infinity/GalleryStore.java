package com.alradhy.worldwindow.infinity;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.SystemClock;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;

/** A destination's photo pack and attribution persist independently of the media queue. */
final class GalleryStore {
    static final int TARGET=20;
    private static final int MAX_IMAGE_BYTES=3*1024*1024;
    private static final Object INDEX_LOCK=new Object();
    static final String PACK_CHANGED="com.alradhy.worldwindow.infinity.PACK_CHANGED";
    private final Context app;
    private final File directory;
    private final AtomicFile index;

    GalleryStore(Context context,Scene scene){
        app=context.getApplicationContext();
        directory=new File(new File(context.getFilesDir(),"pictures"),scene.id);
        index=new AtomicFile(new File(directory,"index.json"));
    }

    static final class Photo {
        final String file,title,credit,license,provider,page,url;
        Photo(JSONObject j){
            file=j.optString("file");title=j.optString("title");credit=j.optString("credit");
            license=j.optString("license");provider=j.optString("provider");page=j.optString("page");
            url=j.optString("url");
        }
    }

    private static final class Source {
        final String url,title,credit,license,provider,page;
        Source(String url,String title,String credit,String license,String provider,String page){
            this.url=url;this.title=title;this.credit=credit;this.license=license;this.provider=provider;this.page=page;
        }
    }

    interface Progress { void update(String message,List<Photo> saved); }
    static final class StorageFullException extends IOException {}

    static final class OfflinePicture {
        final byte[] bytes;
        final String credit,license;
        OfflinePicture(byte[] bytes,String credit,String license){
            this.bytes=bytes;this.credit=credit;this.license=license;
        }
    }

    OfflinePicture fallback(Scene scene,BooleanSupplier cancelled)throws IOException{
        for(Photo photo:saved()){
            if(cancelled.getAsBoolean())return null;
            File file=file(photo);
            if(file.length()>MAX_IMAGE_BYTES)continue;
            byte[] bytes=java.nio.file.Files.readAllBytes(file.toPath());
            if(verifyImage(bytes))return new OfflinePicture(bytes,photo.credit,photo.license);
        }
        long deadline=SystemClock.elapsedRealtime()+25_000;
        BooleanSupplier stop=()->cancelled.getAsBoolean() || SystemClock.elapsedRealtime()>=deadline;
        List<java.util.function.Function<BooleanSupplier,List<Source>>> providers=Arrays.asList(
            limit->commons(scene.title,limit),limit->openverse(scene.title,1,limit),
            limit->"NASA".equals(scene.provider)?nasa(scene.title,limit):Collections.emptyList());
        for(java.util.function.Function<BooleanSupplier,List<Source>> provider:providers){
            if(stop.getAsBoolean())break;
            long providerDeadline=SystemClock.elapsedRealtime()+9_000;
            BooleanSupplier limited=()->stop.getAsBoolean() || SystemClock.elapsedRealtime()>=providerDeadline;
            List<Source> sources=provider.apply(limited);
            for(int i=0;i<Math.min(4,sources.size()) && !limited.getAsBoolean();i++){
                Source candidate=sources.get(i);
                try{
                    byte[] bytes=readImage(candidate.url,limited);
                    if(bytes!=null && !stop.getAsBoolean()){
                        String credit=candidate.credit.isEmpty()?candidate.provider:candidate.credit+" • "+candidate.provider;
                        return new OfflinePicture(bytes,credit,candidate.license+
                            (candidate.page.startsWith("https://")?" • "+candidate.page:""));
                    }
                }catch(IOException ignored){/* Try another verified candidate. */}
            }
        }
        return null;
    }

    private void notifyPackChanged(){
        app.sendBroadcast(new android.content.Intent(PACK_CHANGED).setPackage(app.getPackageName())
            .putExtra("scene_id",directory.getName()));
    }

    List<Photo> saved(){
        List<Photo> photos=new ArrayList<>();
        try{
            byte[] data;
            synchronized(INDEX_LOCK){data=index.readFully();}
            JSONArray rows=new JSONArray(new String(data,StandardCharsets.UTF_8));
            for(int i=0;i<rows.length();i++){
                JSONObject row=rows.optJSONObject(i);
                if(row==null)continue;
                Photo photo=new Photo(row);
                if(photo.file.matches("[0-9a-f]{64}\\.img") && new File(directory,photo.file).length()>0)
                    photos.add(photo);
            }
        }catch(Exception ignored){/* No pack yet, or no readable index. */}
        return photos;
    }

    File file(Photo photo){return new File(directory,photo.file);}

    /** Deletes this destination's pack only; it never touches the media queue's files. */
    boolean delete(){
        synchronized(INDEX_LOCK){
            File[] files=directory.listFiles();
            boolean ok=true;
            if(files!=null)for(File f:files)if(f.isFile() && !f.delete())ok=false;
            if(directory.isDirectory() && !directory.delete())ok=false;
            notifyPackChanged();
            return ok;
        }
    }

    /** Library-wide picture totals, read off the main thread. */
    static final class Summary {
        static final Summary EMPTY=new Summary(Collections.emptyMap(),0,0,0);
        final Map<String,Integer> counts;
        final int packs,photos;
        final long bytes;
        Summary(Map<String,Integer> counts,int packs,int photos,long bytes){
            this.counts=counts;this.packs=packs;this.photos=photos;this.bytes=bytes;
        }
        int count(Scene scene){Integer n=counts.get(scene.id);return n==null?0:n;}
    }

    static Summary summarize(Context context,List<Scene> scenes){
        Map<String,Integer> counts=new HashMap<>();
        int packs=0,total=0;long bytes=0;
        for(Scene scene:scenes){
            GalleryStore store=new GalleryStore(context,scene);
            if(!store.directory.isDirectory())continue;
            List<Photo> saved=store.saved();
            if(saved.isEmpty())continue;
            counts.put(scene.id,saved.size());
            total+=saved.size();
            packs++;
            for(Photo photo:saved)bytes+=store.file(photo).length();
        }
        return new Summary(Collections.unmodifiableMap(counts),packs,total,bytes);
    }

    private static final int PARALLEL=3;
    private static final class Fetched {
        final Source source;final byte[] bytes;
        Fetched(Source source,byte[] bytes){this.source=source;this.bytes=bytes;}
    }

    List<Photo> download(Scene scene,BooleanSupplier cancelled,Progress progress)throws IOException{
        List<Photo> photos=saved();
        if(photos.size()>=TARGET || cancelled.getAsBoolean())return photos;
        long deadline=SystemClock.elapsedRealtime()+180_000;
        BooleanSupplier stop=()->cancelled.getAsBoolean() || SystemClock.elapsedRealtime()>=deadline;
        if(!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory())
            throw new IOException("Picture storage unavailable");
        Set<String> pages=new HashSet<>(), files=new HashSet<>(), attempted=new HashSet<>();
        for(Photo p:photos){
            if(!p.page.isEmpty())pages.add(p.page);
            if(!p.url.isEmpty())attempted.add(p.url);
            files.add(p.file);
        }
        int limit=60;
        // Three verified image fetches run at once; saving stays on this thread, one at a time.
        java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(PARALLEL);
        try{
            for(int round=0;round<2 && photos.size()<TARGET && limit>0 && !stop.getAsBoolean();round++){
                List<List<Source>> providers=new ArrayList<>();
                String query=round==0?scene.title:scene.search;
                progress.update("Finding pictures across sources…",new ArrayList<>(photos));
                providers.add(commons(query,stop));
                providers.add(openverse(query,round+1,stop));
                if(round==0 && "NASA".equals(scene.provider))providers.add(nasa(scene.search,stop));
                // Interleave providers so one slow source never blocks a whole pack.
                List<Source> queue=new ArrayList<>();
                for(int i=0;i<32;i++)for(List<Source> choices:providers){
                    if(i>=choices.size())continue;
                    Source source=choices.get(i);
                    if((source.page.isEmpty() || !pages.contains(source.page)) && attempted.add(source.url))queue.add(source);
                }
                java.util.concurrent.CompletionService<Fetched> completed=
                    new java.util.concurrent.ExecutorCompletionService<>(pool);
                int next=0,inFlight=0;
                while(!stop.getAsBoolean()){
                    while(inFlight<PARALLEL && next<queue.size() && photos.size()+inFlight<TARGET && limit>0){
                        Source source=queue.get(next++);
                        if(!source.page.isEmpty() && pages.contains(source.page))continue;
                        if(directory.getUsableSpace()<24L*1024*1024)throw new StorageFullException();
                        limit--;inFlight++;
                        completed.submit(()->{
                            try{return new Fetched(source,readImage(source.url,stop));}
                            catch(IOException error){return new Fetched(source,null);}
                        });
                    }
                    if(inFlight==0)break;
                    java.util.concurrent.Future<Fetched> result;
                    try{result=completed.poll(400,java.util.concurrent.TimeUnit.MILLISECONDS);}
                    catch(InterruptedException interrupted){Thread.currentThread().interrupt();break;}
                    if(result==null)continue;
                    inFlight--;
                    Fetched fetched;
                    try{fetched=result.get();}
                    catch(Exception error){continue;}
                    if(fetched.bytes==null || stop.getAsBoolean() || photos.size()>=TARGET)continue;
                    Source source=fetched.source;
                    if(!source.page.isEmpty() && pages.contains(source.page))continue;
                    String name=digest(fetched.bytes)+".img";
                    if(files.contains(name))continue;
                    if(directory.getUsableSpace()<24L*1024*1024)throw new StorageFullException();
                    Photo photo=savePhoto(source,name,fetched.bytes,photos);
                    files.add(name);
                    if(!source.page.isEmpty())pages.add(source.page);
                    photos.add(photo);
                    progress.update("Saved from "+source.provider,new ArrayList<>(photos));
                }
            }
        }finally{pool.shutdownNow();}
        if(photos.size()<TARGET && !cancelled.getAsBoolean() && SystemClock.elapsedRealtime()>=deadline)
            throw new IOException("Picture pack timed out • retry missing pictures");
        return photos;
    }

    private Photo savePhoto(Source s,String name,byte[] bytes,List<Photo> current)throws IOException{
        File target=new File(directory,name);
        AtomicFile picture=new AtomicFile(target);
        write(picture,bytes);
        try{
            JSONObject row=new JSONObject().put("file",name).put("title",s.title).put("credit",s.credit)
                .put("license",s.license).put("provider",s.provider).put("page",s.page).put("url",s.url);
            JSONArray rows=new JSONArray();
            for(Photo p:current)rows.put(new JSONObject().put("file",p.file).put("title",p.title)
                .put("credit",p.credit).put("license",p.license).put("provider",p.provider).put("page",p.page)
                .put("url",p.url));
            rows.put(row);
            synchronized(INDEX_LOCK){write(index,rows.toString().getBytes(StandardCharsets.UTF_8));}
            notifyPackChanged();
            return new Photo(row);
        }catch(Exception error){
            picture.delete();
            throw new IOException("Could not save picture index",error);
        }
    }

    private static void write(AtomicFile target,byte[] bytes)throws IOException{
        FileOutputStream out=target.startWrite();
        try{out.write(bytes);target.finishWrite(out);}
        catch(IOException error){target.failWrite(out);throw error;}
    }

    private static byte[] readImage(String url,BooleanSupplier cancelled)throws IOException{
        if(!url.startsWith("https://"))return null;
        HttpURLConnection connection=(HttpURLConnection)new URL(url).openConnection();
        connection.setConnectTimeout(5_000);connection.setReadTimeout(6_000);
        connection.setRequestProperty("User-Agent","ALRADHY-Infinity/1.1 (Android TV gallery)");
        connection.setRequestProperty("Accept","image/jpeg,image/png,image/webp");
        long deadline=SystemClock.elapsedRealtime()+13_000;
        try{
            if(connection.getResponseCode()!=200 || connection.getContentLengthLong()>MAX_IMAGE_BYTES
                || !"https".equals(connection.getURL().getProtocol()))return null;
            long expected=connection.getContentLengthLong();
            try(InputStream in=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] buffer=new byte[16384];
                while(!cancelled.getAsBoolean() && SystemClock.elapsedRealtime()<deadline){
                    int count=in.read(buffer);
                    if(count<0){
                        byte[] data=out.toByteArray();
                        return (expected<0 || data.length==expected) && verifyImage(data)?data:null;
                    }
                    if(out.size()+count>MAX_IMAGE_BYTES)return null;
                    out.write(buffer,0,count);
                }
                return null;
            }
        }finally{connection.disconnect();}
    }

    private static boolean verifyImage(byte[] data){
        BitmapFactory.Options options=new BitmapFactory.Options();
        options.inJustDecodeBounds=true;
        BitmapFactory.decodeByteArray(data,0,data.length,options);
        if(options.outWidth<240 || options.outHeight<140 || options.outWidth>16000 || options.outHeight>16000)return false;
        if(!Arrays.asList("image/jpeg","image/png","image/webp").contains(options.outMimeType))return false;
        options.inJustDecodeBounds=false;
        options.inSampleSize=sample(options.outWidth,options.outHeight,960);
        Bitmap decoded=BitmapFactory.decodeByteArray(data,0,data.length,options);
        if(decoded==null)return false;
        decoded.recycle();
        return true;
    }

    static Bitmap bitmap(File file,int edge){
        BitmapFactory.Options options=new BitmapFactory.Options();
        options.inJustDecodeBounds=true;
        BitmapFactory.decodeFile(file.getAbsolutePath(),options);
        if(!safeImageDimensions(options.outWidth,options.outHeight))return null;
        options.inPreferredConfig=Bitmap.Config.RGB_565;
        options.inSampleSize=sample(options.outWidth,options.outHeight,edge);
        options.inJustDecodeBounds=false;
        return BitmapFactory.decodeFile(file.getAbsolutePath(),options);
    }

    static boolean safeImageDimensions(int width,int height){
        // Progressive JPEG decoders may allocate full-resolution coefficient buffers even
        // when inSampleSize requests a thumbnail. Always bound the source dimensions.
        return width>0&&height>0&&(long)width*height<=24_000_000L;
    }

    private static int sample(int width,int height,int edge){
        int sample=1;
        while(Math.max(width,height)/sample>edge*2)sample*=2;
        return sample;
    }

    private static String digest(byte[] bytes)throws IOException{
        try{
            byte[] sum=MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex=new StringBuilder(64);
            for(byte b:sum)hex.append(Character.forDigit((b&255)>>>4,16)).append(Character.forDigit(b&15,16));
            return hex.toString();
        }catch(java.security.NoSuchAlgorithmException error){throw new IOException(error);}
    }

    private static List<Source> commons(String query,BooleanSupplier cancelled){
        List<Source> results=new ArrayList<>();
        try{
            String url="https://commons.wikimedia.org/w/api.php?action=query&generator=search&gsrnamespace=6"
                +"&gsrlimit=20&prop=imageinfo&iiprop=mime%7Curl%7Cextmetadata&iiurlwidth=960"
                +"&iiextmetadatafilter=Artist%7CLicenseShortName%7CLicenseUrl&format=json&formatversion=2&gsrsearch="
                +Uri.encode(query+" filetype:bitmap");
            JSONObject result=MediaResolver.readMetadata(url,cancelled).optJSONObject("query");
            JSONArray pages=result==null?null:result.optJSONArray("pages");
            if(pages==null)return results;
            for(int i=0;i<pages.length();i++){
                JSONObject page=pages.optJSONObject(i);
                JSONArray rows=page==null?null:page.optJSONArray("imageinfo");
                JSONObject info=rows==null?null:rows.optJSONObject(0);
                if(info==null || !info.optString("mime").startsWith("image/"))continue;
                JSONObject metadata=info.optJSONObject("extmetadata");
                if(metadata==null)continue;
                String license=MediaResolver.field(metadata,"LicenseShortName");
                String lower=license.toLowerCase(Locale.ROOT);
                if(!lower.matches("cc by(?:-sa)?(?: [0-9.]+)?|cc0(?: [0-9.]+)?|public domain|pdm|pd"))continue;
                String creator=MediaResolver.field(metadata,"Artist");
                if(creator.isEmpty() && lower.startsWith("cc by"))continue;
                String image=info.optString("thumburl");
                if(!image.startsWith("https://"))continue;
                String title=page.optString("title").replaceFirst("^File:","").replaceFirst("\\.[^.]+$","");
                String licenseUrl=MediaResolver.field(metadata,"LicenseUrl");
                results.add(new Source(image,title,creator,license+(licenseUrl.isEmpty()?"":" • "+licenseUrl),
                    "Wikimedia Commons",info.optString("descriptionurl")));
            }
        }catch(Exception ignored){/* Other providers remain available. */}
        return results;
    }

    private static List<Source> openverse(String query,int page,BooleanSupplier cancelled){
        List<Source> results=new ArrayList<>();
        try{
            String url="https://api.openverse.org/v1/images/?q="+Uri.encode(query)
                +"&page_size=20&page="+page+"&license=by,by-sa,cc0,pdm&mature=false";
            JSONArray rows=MediaResolver.readMetadata(url,cancelled).optJSONArray("results");
            if(rows==null)return results;
            for(int i=0;i<rows.length();i++){
                JSONObject row=rows.optJSONObject(i);
                if(row==null || row.optBoolean("mature",false))continue;
                String license=row.optString("license");
                if(!Arrays.asList("by","by-sa","cc0","pdm").contains(license))continue;
                String creator=row.optString("creator");
                if(creator.equals("null"))creator="";
                if(creator.isEmpty() && license.startsWith("by"))continue;
                String image=row.optString("url");
                if(row.optInt("width",0)>2500 || !image.startsWith("https://"))image=row.optString("thumbnail");
                if(!image.startsWith("https://"))continue;
                String source=row.optString("source","Openverse");
                results.add(new Source(image,row.optString("title"),creator,row.optString("license_url",license),
                    "Openverse / "+source,row.optString("foreign_landing_url")));
            }
        }catch(Exception ignored){/* Other providers remain available. */}
        return results;
    }

    private static List<Source> nasa(String query,BooleanSupplier cancelled){
        List<Source> results=new ArrayList<>();
        try{
            String url="https://images-api.nasa.gov/search?media_type=image&page_size=20&q="+Uri.encode(query);
            JSONObject collection=MediaResolver.readMetadata(url,cancelled).optJSONObject("collection");
            JSONArray rows=collection==null?null:collection.optJSONArray("items");
            if(rows==null)return results;
            for(int i=0;i<rows.length();i++){
                JSONObject row=rows.optJSONObject(i);
                JSONArray data=row==null?null:row.optJSONArray("data");
                JSONArray links=row==null?null:row.optJSONArray("links");
                JSONObject metadata=data==null?null:data.optJSONObject(0);
                JSONObject preview=links==null?null:links.optJSONObject(0);
                if(metadata==null || preview==null)continue;
                String id=metadata.optString("nasa_id");
                String image=preview.optString("href");
                if(id.isEmpty() || !image.startsWith("https://"))continue;
                String credit=metadata.optString("photographer");
                if(!credit.isEmpty() && !credit.toLowerCase(Locale.ROOT).contains("nasa"))continue;
                results.add(new Source(image,metadata.optString("title"),"NASA",
                    "NASA media use guidelines • https://www.nasa.gov/nasa-brand-center/images-and-media/",
                    "NASA","https://images.nasa.gov/details/"+Uri.encode(id)));
            }
        }catch(Exception ignored){/* Other providers remain available. */}
        return results;
    }
}
