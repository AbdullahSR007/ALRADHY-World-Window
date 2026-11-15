package com.alradhy.worldwindow.infinity;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import org.json.*;
import java.util.*;
import java.time.*;

/** Additive user metadata. Never rewrites the existing library or picture indexes. */
final class WorldStore {
    final SharedPreferences prefs;
    WorldStore(Context context){prefs=context.getSharedPreferences("infinity_world_v1",Context.MODE_PRIVATE);}
    boolean favourite(Scene s){return prefs.getBoolean("star:"+s.id,false);}
    boolean photoFavourite(Scene s,String file){return prefs.getBoolean("photo:"+s.id+":"+file,false);}
    boolean hasFavouritePhotos(Scene s){
        String prefix="photo:"+s.id+":";
        for(Map.Entry<String,?> entry:prefs.getAll().entrySet())
            if(entry.getKey().startsWith(prefix)&&Boolean.TRUE.equals(entry.getValue()))return true;
        return false;
    }
    void toggle(Scene s){prefs.edit().putBoolean("star:"+s.id,!favourite(s)).apply();}
    void togglePhoto(Scene s,String file){prefs.edit().putBoolean("photo:"+s.id+":"+file,!photoFavourite(s,file)).apply();}
    JSONObject info(Scene s){try{return new JSONObject(prefs.getString("info:"+s.id,"{}"));}catch(Exception e){return new JSONObject();}}
    String zone(Scene s){return info(s).optString("zone",s.id.startsWith("U")?"Unknown":s.zone);}
    String daylight(Scene s){
        try{
            JSONObject data=info(s),daily=data.optJSONObject("daily");
            if(daily==null)return "Sun times unavailable";
            ZonedDateTime now=ZonedDateTime.now(ZoneId.of(zone(s)));
            JSONArray dates=daily.getJSONArray("time");
            for(int i=0;i<dates.length();i++)if(dates.getString(i).equals(now.toLocalDate().toString())){
                String rise=daily.getJSONArray("sunrise").optString(i),set=daily.getJSONArray("sunset").optString(i);
                LocalDateTime r=LocalDateTime.parse(rise),t=LocalDateTime.parse(set),n=now.toLocalDateTime();
                return (n.isBefore(r)||!n.isBefore(t)?"Night":"Daylight")+" • ↑ "+r.toLocalTime()+" ↓ "+t.toLocalTime();
            }
        }catch(Exception ignored){}
        return "Sun times need refresh";
    }
    List<Scene> custom(){
        List<Scene> out=new ArrayList<>();
        try{JSONArray list=new JSONArray(prefs.getString("places","[]"));
            for(int i=0;i<list.length();i++){JSONObject o=list.getJSONObject(i);
                out.add(new Scene(o.getString("id"),"CITIES",o.getString("title"),"","Your place","Etc/UTC",o.getString("title"),"COMMONS"));
            }
        }catch(Exception ignored){}
        return out;
    }
    synchronized Scene add(JSONObject candidate)throws Exception{
        String title=candidate.getString("title"),id="U"+candidate.getLong("pageid");
        JSONArray list=new JSONArray(prefs.getString("places","[]"));
        for(int i=0;i<list.length();i++)if(id.equals(list.getJSONObject(i).getString("id")))
            throw new IllegalArgumentException("This place is already in your library");
        list.put(new JSONObject().put("id",id).put("title",title));
        if(!prefs.edit().putString("places",list.toString()).putString("info:"+id,candidate.toString()).commit())
            throw new java.io.IOException("Could not save this place");
        return new Scene(id,"CITIES",title,"","Your place","Etc/UTC",title,"COMMONS");
    }
    static List<JSONObject> search(String query)throws Exception{
        JSONObject response=MediaResolver.readMetadata("https://en.wikipedia.org/w/api.php?action=query&format=json&formatversion=2&generator=search&gsrnamespace=0&gsrlimit=8&gsrsearch="+Uri.encode(query)+"&prop=coordinates%7Cextracts&exintro=1&explaintext=1&exlimit=max&exchars=700",()->Thread.currentThread().isInterrupted());
        List<JSONObject> out=new ArrayList<>();
        JSONObject result=response.optJSONObject("query");
        JSONArray pages=result==null?null:result.optJSONArray("pages");
        if(pages!=null)for(int i=0;i<pages.length();i++){
            JSONObject page=pages.getJSONObject(i);
            if(page.optJSONArray("coordinates")!=null)out.add(page);
        }
        out.sort(Comparator.comparingInt(p->p.optInt("index",100)));
        return out;
    }
    void refresh(Scene scene)throws Exception{
        JSONObject data=info(scene);
        if(!data.has("pageid")){
            String title=scene.title.equals("New York")?"New York City":scene.title;
            JSONObject result=MediaResolver.readMetadata("https://en.wikipedia.org/w/api.php?action=query&format=json&formatversion=2&redirects=1&prop=coordinates%7Cextracts&exintro=1&explaintext=1&exchars=700&titles="+Uri.encode(title),()->Thread.currentThread().isInterrupted());
            JSONArray pages=result.getJSONObject("query").getJSONArray("pages");
            JSONObject page=pages.getJSONObject(0);
            if(page.has("missing"))return;
            data=page;
        }
        // Save facts even if the sun service is temporarily unavailable.
        prefs.edit().putString("info:"+scene.id,data.toString()).commit();
        JSONArray coords=data.optJSONArray("coordinates");
        if(coords!=null && coords.length()>0){
            JSONObject c=coords.getJSONObject(0);
            JSONObject sun=MediaResolver.readMetadata("https://api.open-meteo.com/v1/forecast?latitude="+c.getDouble("lat")+"&longitude="+c.getDouble("lon")+"&daily=sunrise,sunset&timezone=auto&forecast_days=7",()->Thread.currentThread().isInterrupted());
            String zone=sun.getString("timezone");ZoneId.of(zone);
            data.put("zone",zone).put("daily",sun.getJSONObject("daily"));
            prefs.edit().putString("info:"+scene.id,data.toString()).commit();
        }
    }
}
