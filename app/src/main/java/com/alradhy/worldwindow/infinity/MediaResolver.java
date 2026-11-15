package com.alradhy.worldwindow.infinity;

import android.net.Uri;
import android.os.SystemClock;
import android.text.Html;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Discovers CURRENT media URLs from official APIs. No time-limited raw hotlinks in the catalog.
 * Commons results require explicit open licenses; NASA videos/images show NASA attribution.
 * The user can cache the entire resolved asset locally for permanent offline playback.
 */
public final class MediaResolver {
    private final LibraryStore store;
    public MediaResolver(LibraryStore store) { this.store=store; }

    public boolean resolve(Scene scene) {
        return resolve(scene,()->false);
    }

    boolean resolve(Scene scene,BooleanSupplier cancelled) {
        QueryBudget budget=new QueryBudget(SystemClock.elapsedRealtime()+24_000,cancelled);
        synchronized(scene) {
            if(cancelled.getAsBoolean())return false;
            if(scene.isOffline() || scene.hasSource())return true;
            scene.resolving=true;scene.error="";
            try {
                boolean ok="NASA".equals(scene.provider) ? nasa(scene,budget) : commons(scene,budget);
                if(!ok)scene.error="No suitable media found. Retry discovery later.";
                store.save();
                return ok;
            }catch(Exception e) {
                if(cancelled.getAsBoolean())return false;
                scene.error="Source unavailable: "+e.getClass().getSimpleName();
                store.save();
                return false;
            }finally { scene.resolving=false; }
        }
    }
    public void refresh(Scene s) {
        invalidateSource(s);
        resolve(s);
    }
    void invalidateSource(Scene s) {
        synchronized(s){
            if(s.isOffline())return;
            s.mediaUrl="";s.posterUrl="";s.error="";
        }
    }
    private boolean duplicate(String url, Scene self) {
        for(Scene s:store.scenes)if(s!=self && url.equals(s.mediaUrl) && !url.isEmpty())return true;
        return false;
    }
    private boolean commons(Scene s,QueryBudget budget)throws Exception{
        String endpoint="https://commons.wikimedia.org/w/api.php?action=query&generator=search"
            +"&gsrnamespace=6&gsrlimit=20&prop=imageinfo&iiprop=url%7Csize%7Cmime%7Cextmetadata"
            +"&iiurlwidth=1280&format=json&formatversion=2&gsrsearch=";
        // Prefer landscape images as a reliable first-run experience, and playable clips when found.
        Candidate photo=null;
        try{photo=pickCommons(endpoint+Uri.encode(s.search+" filetype:bitmap"),s,false,budget.limit(8_000));}
        catch(java.io.IOException ignored){/* Broader search below can still resolve the destination. */}
        if(photo==null && budget.hasTime()){
            try{photo=pickCommons(endpoint+Uri.encode(s.title+" filetype:bitmap"),s,false,budget.limit(8_000));}
            catch(java.io.IOException ignored){/* Try the video query if time remains. */}
        }
        Candidate video=null;
        try{video=pickCommons(endpoint+Uri.encode(s.search+" filetype:video"),s,true,budget.limit(8_000));}
        catch(Exception ignored){/* photographic fallback remains usable */}
        if(photo==null && video==null)return false;
        Candidate selected=video!=null ? video : photo;
        s.mediaUrl=selected.url;s.mime=selected.mime;s.sourceUrl=selected.page;
        s.credit=selected.credit;s.license=selected.license;
        s.posterUrl=(photo!=null ? photo.poster : selected.poster);
        if(s.posterUrl.isEmpty())s.posterUrl=selected.poster;
        return true;
    }
    private Candidate pickCommons(String url,Scene scene,boolean videoOnly,QueryBudget budget)throws Exception {
        JSONObject root=readJson(url,budget);
        JSONArray pages=root.optJSONObject("query")!=null?
                root.getJSONObject("query").optJSONArray("pages"):null;
        if(pages==null)return null;
        Candidate best=null;int bestScore=-1000;
        Set<String> keywords=new HashSet<>();
        for(String token:scene.search.toLowerCase(Locale.US).split("[^a-z0-9]+"))
            if(token.length()>3)keywords.add(token);
        for(int i=0;i<pages.length();i++){
            JSONObject item=pages.optJSONObject(i);
            if(item==null)continue;
            JSONArray infos=item.optJSONArray("imageinfo");
            if(infos==null || infos.length()==0)continue;
            JSONObject info=infos.optJSONObject(0);
            if(info==null)continue;
            String mime=info.optString("mime","").toLowerCase(Locale.US);
            boolean video=mime.startsWith("video/");
            if(videoOnly!=video)continue;
            if(!video && !(mime.equals("image/jpeg")||mime.equals("image/png")||
                mime.equals("image/webp")))continue;
            if(!video && info.optLong("width",0)<850)continue;
            String raw=info.optString("url","");
            if(!raw.startsWith("https://") || duplicate(raw,scene))continue;
            JSONObject ex=info.optJSONObject("extmetadata");
            if(ex==null)continue;
            String license=field(ex,"LicenseShortName");
            String l=license.toLowerCase(Locale.US);
            if(!(l.contains("cc by")||l.contains("cc0")||l.contains("public domain")||
                 l.equals("pd")||l.contains("pdm")))continue;
            String title=item.optString("title","").toLowerCase(Locale.US);
            int score=0;
            for(String word:keywords)if(title.contains(word))score+=3;
            if(title.contains("panorama")||title.contains("aerial")||
               title.contains("timelapse")||title.contains("night"))score+=2;
            if(title.contains("logo")||title.contains("diagram")||
               title.contains("map")||title.contains("icon"))score-=12;
            if(video)score+=3;
            if(score>bestScore){
                bestScore=score;
                Candidate c=new Candidate();c.url=raw;c.mime=mime;
                c.poster=info.optString("thumburl",raw);
                c.page=info.optString("descriptionurl","https://commons.wikimedia.org/");
                c.license=license;
                String artist=field(ex,"Artist").replaceAll("<[^>]*>"," ").replaceAll("\\s+"," ").trim();
                c.credit=artist.length()>120?artist.substring(0,120):artist;
                if(c.credit.isEmpty())c.credit="Wikimedia Commons contributor";
                best=c;
            }
        }
        return best;
    }
    static String field(JSONObject parent,String key){
        JSONObject v=parent.optJSONObject(key);
        return v==null?"":Html.fromHtml(v.optString("value",""),Html.FROM_HTML_MODE_LEGACY).toString().trim();
    }

    private boolean nasa(Scene s,QueryBudget budget)throws Exception{
        // Reserve time for images when the video search or its asset manifests are slow.
        try{
            if(nasaMedia(s,true,budget.limit(11_000)))return true;
        }catch(java.io.IOException ignored){/* Try the image search within the remaining budget. */}
        return nasaMedia(s,false,budget);
    }

    private boolean nasaMedia(Scene s,boolean video,QueryBudget budget)throws Exception{
        String search="https://images-api.nasa.gov/search?media_type="+(video?"video":"image")
            +"&page_size=20&q="+Uri.encode(s.search);
        JSONObject response=readJson(search,budget);
        JSONObject collection=response.optJSONObject("collection");
        JSONArray hits=collection==null?null:collection.optJSONArray("items");
        if(hits==null)return false;
        int checked=0;
        for(int i=0;i<hits.length() && checked<4 && budget.hasTime();i++){
            JSONObject item=hits.optJSONObject(i);if(item==null)continue;
            JSONArray d=item.optJSONArray("data");if(d==null||d.length()==0)continue;
            JSONObject meta=d.optJSONObject(0);if(meta==null)continue;
            String id=meta.optString("nasa_id","");
            if(id.isEmpty())continue;
            checked++;
            String media="";
            try{media=chooseNasaAsset(id,video,budget.limit(4_000));}
            catch(java.io.IOException ignored){continue;}
            if(media.isEmpty()||duplicate(media,s))continue;
            JSONArray links=item.optJSONArray("links");
            JSONObject first=links==null?null:links.optJSONObject(0);
            String fallback=video?"":media;
            s.mediaUrl=media;s.mime=video?"video/mp4":"image/jpeg";
            s.posterUrl=first==null?fallback:first.optString("href",fallback);
            s.sourceUrl="https://images.nasa.gov/details/"+Uri.encode(id);
            s.credit="NASA • "+meta.optString("title",s.title);
            s.license="NASA media — check usage guidelines";
            return true;
        }
        return false;
    }
    private String chooseNasaAsset(String id,boolean video,QueryBudget budget)throws Exception{
        JSONObject root=readJson("https://images-api.nasa.gov/asset/"+Uri.encode(id),budget);
        JSONArray files=root.optJSONObject("collection")!=null?
            root.getJSONObject("collection").optJSONArray("items"):null;
        if(files==null)return "";
        String best="";int bestScore=-1000;
        for(int i=0;i<files.length();i++){
            JSONObject obj=files.optJSONObject(i);if(obj==null)continue;
            String href=obj.optString("href","");
            if(!href.startsWith("https://"))continue;
            String lower=href.toLowerCase(Locale.US);
            if(video?!lower.contains(".mp4"):!(lower.contains(".jpg")||lower.contains(".jpeg")))continue;
            int score=0;
            if(lower.contains("~medium"))score+=6;
            if(lower.contains("~large"))score+=4;
            if(lower.contains("~orig"))score-=6;
            if(lower.contains("1080"))score+=4;
            if(lower.contains("720"))score+=5;
            if(score>bestScore){bestScore=score;best=href;}
        }
        return best;
    }

    static JSONObject readMetadata(String link,BooleanSupplier cancelled)throws Exception {
        return readJson(link,new QueryBudget(SystemClock.elapsedRealtime()+12_000,cancelled));
    }

    private static JSONObject readJson(String link,QueryBudget budget)throws Exception {
        int timeout=budget.timeout();
        HttpURLConnection conn=(HttpURLConnection)new URL(link).openConnection();
        conn.setConnectTimeout(timeout);conn.setReadTimeout(timeout);
        conn.setRequestProperty("User-Agent","ALRADHY-Infinity/1.0 (personal Android TV media browser)");
        conn.setRequestProperty("Accept","application/json");
        conn.setInstanceFollowRedirects(true);
        try{
            int status=conn.getResponseCode();
            if(status!=200)throw new java.io.IOException("HTTP "+status);
            budget.timeout();
            try(InputStream in=conn.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] buf=new byte[16384];int n;int total=0;
                while(true){
                    budget.timeout();
                    if((n=in.read(buf))==-1)break;
                    total+=n;if(total>4_000_000)throw new java.io.IOException("Metadata too large");
                    out.write(buf,0,n);
                }
                return new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
            }
        }finally{conn.disconnect();}
    }
    private static final class QueryBudget {
        private final long deadline;
        private final BooleanSupplier cancelled;
        QueryBudget(long deadline,BooleanSupplier cancelled){this.deadline=deadline;this.cancelled=cancelled;}
        QueryBudget limit(long millis){
            return new QueryBudget(Math.min(deadline,SystemClock.elapsedRealtime()+millis),cancelled);
        }
        boolean hasTime(){return !cancelled.getAsBoolean() && deadline>SystemClock.elapsedRealtime();}
        int timeout()throws SocketTimeoutException{
            if(cancelled.getAsBoolean())throw new SocketTimeoutException("Discovery paused");
            long remaining=deadline-SystemClock.elapsedRealtime();
            if(remaining<=0)throw new SocketTimeoutException("Discovery timed out");
            return (int)Math.min(5_000,remaining);
        }
    }
    private static final class Candidate {
        String url="", mime="", poster="", page="", license="", credit="";
    }

}
