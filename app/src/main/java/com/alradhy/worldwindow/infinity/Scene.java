package com.alradhy.worldwindow.infinity;

import org.json.JSONException;
import org.json.JSONObject;

/** Each scene owns its metadata and offline asset state; never share media between scenes. */
public final class Scene {
    public final String id, category, title, arabic, subtitle, zone, search, provider;
    public volatile String posterUrl = "", mediaUrl = "", mime = "", localPoster = "",
            localMedia = "", credit = "", license = "", sourceUrl = "", error = "";
    public volatile long bytes = 0, total = 0;
    public volatile boolean resolving = false, downloading = false;

    public Scene(String id, String category, String title, String arabic, String subtitle,
                 String zone, String search, String provider) {
        this.id=id; this.category=category; this.title=title; this.arabic=arabic;
        this.subtitle=subtitle; this.zone=zone; this.search=search; this.provider=provider;
    }
    public boolean isVideo() { return mime.startsWith("video/"); }
    public boolean hasSource() { return mediaUrl != null && !mediaUrl.isEmpty(); }
    public boolean isOffline() {
        return localMedia != null && !localMedia.isEmpty() &&
               new java.io.File(localMedia).isFile() && new java.io.File(localMedia).length()>0;
    }
    public String status() {
        if (downloading) return "DOWNLOADING";
        if (isOffline()) return "OFFLINE READY";
        if (resolving) return "DISCOVERING";
        if (hasSource()) return "ONLINE READY";
        if (!error.isEmpty()) return "RETRY AVAILABLE";
        return "AWAITING DISCOVERY";
    }
    public JSONObject saved() throws JSONException {
        JSONObject o=new JSONObject();
        o.put("poster",posterUrl);o.put("media",mediaUrl);o.put("mime",mime);
        o.put("localPoster",localPoster);o.put("localMedia",localMedia);
        o.put("credit",credit);o.put("license",license);o.put("source",sourceUrl);
        o.put("bytes",bytes);o.put("total",total);
        return o;
    }
    public void restore(JSONObject o) {
        if(o==null)return;
        posterUrl=o.optString("poster","");mediaUrl=o.optString("media","");
        mime=o.optString("mime","");localPoster=o.optString("localPoster","");
        localMedia=o.optString("localMedia","");credit=o.optString("credit","");
        license=o.optString("license","");sourceUrl=o.optString("source","");
        bytes=o.optLong("bytes",0); total=o.optLong("total",0);
    }
}
