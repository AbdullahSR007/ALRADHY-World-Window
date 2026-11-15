package com.alradhy.worldwindow.infinity;

import android.net.Uri;
import android.text.Html;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Discovers CURRENT media URLs from official APIs. No time-limited raw hotlinks in the catalog.
 * Commons results require explicit open licenses; NASA videos/images show NASA attribution.
 * The user can cache the entire resolved asset locally for permanent offline playback.
 */
public final class MediaResolver {
    private final LibraryStore store;
    public MediaResolver(LibraryStore store) { this.store=store; }

    public boolean resolve(Scene scene) {
        synchronized(scene) {
            if(scene.isOffline() || scene.hasSource())return true;
            scene.resolving=true;scene.error="";
            try {
                boolean ok="NASA".equals(scene.provider) ? nasa(scene) : commons(scene);
                if(!ok)scene.error="No suitable media found. Retry discovery later.";
                store.save();
                return ok;
            }catch(Exception e) {
                scene.error="Source unavailable: "+e.getClass().getSimpleName();
                store.save();
                return false;
            }finally { scene.resolving=false; }
        }
    }
    public void refresh(Scene s) {
        synchronized(s){
            if(s.isOffline())return;
            s.mediaUrl="";s.posterUrl="";s.error="";
        }
        resolve(s);
    }
    private boolean duplicate(String url, Scene self) {
        for(Scene s:store.scenes)if(s!=self && url.equals(s.mediaUrl) && !url.isEmpty())return true;
        return false;
    }
    private boolean commons(Scene s)throws Exception{
        String endpoint="https://commons.wikimedia.org/w/api.php?action=query&generator=search"
            +"&gsrnamespace=6&gsrlimit=20&prop=imageinfo&iiprop=url%7Csize%7Cmime%7Cextmetadata"
            +"&iiurlwidth=1280&format=json&formatversion=2&gsrsearch=";
        // Prefer landscape images as a reliable first-run experience, and playable clips when found.
        Candidate photo=pickCommons(endpoint+Uri.encode(s.search),s,false);
        Candidate video=null;
        try{video=pickCommons(endpoint+Uri.encode(s.search+" filetype:video"),s,true);}
        catch(Exception ignored){/* photographic fallback remains usable */}
        if(photo==null && video==null)return false;
        Candidate selected=video!=null ? video : photo;
        s.mediaUrl=selected.url;s.mime=selected.mime;s.sourceUrl=selected.page;
        s.credit=selected.credit;s.license=selected.license;
        s.posterUrl=(photo!=null ? photo.poster : selected.poster);
        if(s.posterUrl.isEmpty())s.posterUrl=selected.poster;
        return true;
    }
    private Candidate pickCommons(String url,Scene scene,boolean videoOnly)throws Exception {
        JSONObject root=readJson(url);
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
    private static String field(JSONObject parent,String key){
        JSONObject v=parent.optJSONObject(key);
        return v==null?"":Html.fromHtml(v.optString("value",""),Html.FROM_HTML_MODE_LEGACY).toString().trim();
    }

    private boolean nasa(Scene s)throws Exception{
        String search="https://images-api.nasa.gov/search?media_type=video&page_size=20&q="+Uri.encode(s.search);
        JSONObject response=readJson(search);
        JSONArray hits=response.optJSONObject("collection")!=null?
            response.getJSONObject("collection").optJSONArray("items"):null;
        if(hits!=null)for(int i=0;i<hits.length();i++){
            JSONObject item=hits.optJSONObject(i);if(item==null)continue;
            JSONArray d=item.optJSONArray("data");if(d==null||d.length()==0)continue;
            JSONObject meta=d.optJSONObject(0);if(meta==null)continue;
            String id=meta.optString("nasa_id","");
            if(id.isEmpty())continue;
            String media="";
            try{media=chooseNasaAsset(id,true);}catch(Exception ignored){continue;}
            if(media.isEmpty()||duplicate(media,s))continue;
            JSONArray links=item.optJSONArray("links");
            String poster=(links!=null&&links.length()>0)?
                links.optJSONObject(0).optString("href",""):"";
            s.mediaUrl=media;s.mime="video/mp4";s.posterUrl=poster;
            s.sourceUrl="https://images.nasa.gov/details/"+Uri.encode(id);
            s.credit="NASA • "+meta.optString("title",s.title);
            s.license="NASA media — check usage guidelines";
            return true;
        }
        // NASA video search can yield empty manifests; use original NASA photos as fallback.
        JSONObject fallback=readJson("https://images-api.nasa.gov/search?media_type=image&page_size=20&q="+Uri.encode(s.search));
        JSONArray items=fallback.optJSONObject("collection")!=null?
            fallback.getJSONObject("collection").optJSONArray("items"):null;
        if(items==null)return false;
        for(int i=0;i<items.length();i++){
            JSONObject it=items.optJSONObject(i);if(it==null)continue;
            JSONArray data=it.optJSONArray("data");if(data==null||data.length()==0)continue;
            JSONObject metadata=data.optJSONObject(0);if(metadata==null)continue;
            String id=metadata.optString("nasa_id","");
            if(id.isEmpty())continue;
            String media="";
            try{media=chooseNasaAsset(id,false);}catch(Exception ignored){continue;}
            if(media.isEmpty()||duplicate(media,s))continue;
            JSONArray links=it.optJSONArray("links");
            s.mediaUrl=media;s.posterUrl=(links!=null&&links.length()>0)?
                links.optJSONObject(0).optString("href",media):media;
            s.mime="image/jpeg";s.credit="NASA • "+metadata.optString("title",s.title);
            s.license="NASA media — check usage guidelines";
            s.sourceUrl="https://images.nasa.gov/details/"+Uri.encode(id);
            return true;
        }
        return false;
    }
    private String chooseNasaAsset(String id,boolean video)throws Exception{
        JSONObject root=readJson("https://images-api.nasa.gov/asset/"+Uri.encode(id));
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

    private static JSONObject readJson(String link)throws Exception {
        HttpURLConnection conn=(HttpURLConnection)new URL(link).openConnection();
        conn.setConnectTimeout(15000);conn.setReadTimeout(22000);
        conn.setRequestProperty("User-Agent","ALRADHY-Infinity/1.0 (personal Android TV media browser)");
        conn.setRequestProperty("Accept","application/json");
        conn.setInstanceFollowRedirects(true);
        try{
            if(conn.getResponseCode()!=200)throw new java.io.IOException("HTTP "+conn.getResponseCode());
            try(InputStream in=conn.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] buf=new byte[16384];int n;int total=0;
                while((n=in.read(buf))!=-1){
                    total+=n;if(total>4_000_000)throw new java.io.IOException("Metadata too large");
                    out.write(buf,0,n);
                }
                return new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
            }
        }finally{conn.disconnect();}
    }
    private static final class Candidate {
        String url="", mime="", poster="", page="", license="", credit="";
    }

}
