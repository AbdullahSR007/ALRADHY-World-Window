package com.alradhy.worldwindow.infinity;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import org.json.*;
import java.util.*;
import java.time.*;

/** Additive user metadata. Never rewrites the existing library or picture indexes. */
final class WorldStore {
    static final String[] PLACE_CATEGORIES={"CITIES","NATURE","OCEANS","SPACE"};
    static final String[] CATEGORY_NAMES={"المدن","الطبيعة","المحيطات","الفضاء"};
    static String categoryName(String category){int i=Arrays.asList(PLACE_CATEGORIES).indexOf(category);return i<0?"الأماكن":CATEGORY_NAMES[i];}
    static String validCategory(String category){if(!Arrays.asList(PLACE_CATEGORIES).contains(category))throw new IllegalArgumentException("اختر تصنيفًا صالحًا");return category;}
    static final String NO_ARABIC="الوصف العربي غير محفوظ بعد. اختر تحديث الوصف أثناء الاتصال بالإنترنت؛ وإذا لم يتوفر مصدر عربي فسيظهر ذلك هنا.";
    static String pictureStatus(String status){return status.contains("incomplete")||status.contains("packs complete")?"":status;}
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
    String description(Scene s){
        String value=info(s).optString("extractAr","");
        return value.matches("(?s).*[\\u0600-\\u06ff].*")?value:ArabicDescriptions.forScene(s);
    }
    String zone(Scene s){return info(s).optString("zone",s.id.startsWith("U")?"Unknown":s.zone);}
    String daylightArabic(Scene s){
        if(s.category.equals("SPACE"))return "مشاهد الفضاء";
        return daylight(s).replace("Sun times unavailable","مواقيت الشمس غير متوفرة").replace("Sun times need refresh","مواقيت الشمس بحاجة إلى تحديث").replace("Daylight","نهار").replace("Night","ليل");
    }
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
                String category=o.optString("category","CITIES");
                if(!Arrays.asList(PLACE_CATEGORIES).contains(category))category="CITIES";
                out.add(customScene(o.getString("id"),o.getString("title"),o.optString("arabic",""),category));
            }
        }catch(Exception ignored){}
        return out;
    }
    synchronized Scene add(JSONObject candidate)throws Exception{
        return add(candidate,"CITIES");
    }
    private Scene customScene(String id,String title,String arabic,String category){
        String search=title;
        try{search=new JSONObject(prefs.getString("info:"+id,"{}")).optString("searchTitle",title);}catch(Exception ignored){}
        return new Scene(id,category,title,arabic,"مكان أضفته إلى "+categoryName(category),"Etc/UTC",search,category.equals("SPACE")?"NASA":"COMMONS");
    }
    synchronized Scene add(JSONObject candidate,String category)throws Exception{
        validCategory(category);
        String title=candidate.getString("title"),id=(candidate.optString("lang").equals("ar")?"UA":"U")+candidate.getLong("pageid");
        String arabic=candidate.optString("titleAr",candidate.optString("lang").equals("ar")?title:"");
        JSONArray list=new JSONArray(prefs.getString("places","[]"));
        for(int i=0;i<list.length();i++)if(id.equals(list.getJSONObject(i).getString("id")))
            throw new IllegalArgumentException("This place is already in your library");
        list.put(new JSONObject().put("id",id).put("title",title).put("arabic",arabic).put("category",category));
        if(!prefs.edit().putString("places",list.toString()).putString("info:"+id,candidate.toString()).commit())
            throw new java.io.IOException("Could not save this place");
        return customScene(id,title,arabic,category);
    }
    synchronized boolean isCustom(Scene scene){
        return custom().stream().anyMatch(s->s.id.equals(scene.id));
    }
    synchronized void remove(Scene scene)throws Exception{
        if(!isCustom(scene))throw new IllegalArgumentException("لا يمكن حذف الأماكن الأصلية");
        JSONArray old=new JSONArray(prefs.getString("places","[]")),remaining=new JSONArray();
        for(int i=0;i<old.length();i++)if(!old.getJSONObject(i).getString("id").equals(scene.id))remaining.put(old.getJSONObject(i));
        SharedPreferences.Editor edit=prefs.edit().putString("places",remaining.toString()).remove("info:"+scene.id).remove("star:"+scene.id);
        for(String key:prefs.getAll().keySet())if(key.startsWith("photo:"+scene.id+":"))edit.remove(key);
        if(!edit.commit())throw new java.io.IOException("تعذر حفظ حذف المكان");
    }
    static List<JSONObject> search(String query)throws Exception{
        return search(query,"CITIES");
    }
    static List<JSONObject> search(String query,String category)throws Exception{
        validCategory(category);
        String lang=query.matches(".*[\\u0600-\\u06ff].*")?"ar":"en";
        JSONObject response=MediaResolver.readMetadata("https://"+lang+".wikipedia.org/w/api.php?action=query&format=json&formatversion=2&generator=search&gsrnamespace=0&gsrlimit=8&gsrsearch="+Uri.encode(query)+"&prop=coordinates%7Cextracts&exintro=1&explaintext=1&exlimit=max&exchars=700",()->Thread.currentThread().isInterrupted());
        List<JSONObject> out=new ArrayList<>();
        JSONObject result=response.optJSONObject("query");
        JSONArray pages=result==null?null:result.optJSONArray("pages");
        if(pages!=null)for(int i=0;i<pages.length();i++){
            JSONObject page=pages.getJSONObject(i);
            page.put("lang",lang);
            if(!category.equals("CITIES")||page.optJSONArray("coordinates")!=null)out.add(page);
        }
        out.sort(Comparator.comparingInt(p->p.optInt("index",100)));
        return out;
    }
    static void arabic(JSONObject data)throws Exception{
        if(data.optString("lang","en").equals("ar")){
            data.put("extractAr",data.optString("extract","الوصف العربي غير متوفر في المصدر"));
            data.put("titleAr",data.optString("title")).put("pageidAr",data.optLong("pageid"));
            try{
                JSONArray links=MediaResolver.readMetadata("https://ar.wikipedia.org/w/api.php?action=query&format=json&formatversion=2&prop=langlinks&lllang=en&lllimit=1&pageids="+data.getLong("pageid"),()->Thread.currentThread().isInterrupted()).getJSONObject("query").getJSONArray("pages").getJSONObject(0).optJSONArray("langlinks");
                if(links!=null&&links.length()>0)data.put("searchTitle",links.getJSONObject(0).getString("title"));
            }catch(Exception ignored){/* Arabic facts remain usable if cross-language lookup fails. */}
            return;
        }
        JSONObject response=MediaResolver.readMetadata("https://en.wikipedia.org/w/api.php?action=query&format=json&formatversion=2&prop=langlinks&lllang=ar&lllimit=1&pageids="+data.getLong("pageid"),()->Thread.currentThread().isInterrupted());
        JSONObject page=response.getJSONObject("query").getJSONArray("pages").getJSONObject(0);
        JSONArray links=page.optJSONArray("langlinks");
        if(links==null||links.length()==0){data.put("extractAr","لا يتوفر وصف عربي لهذا المكان في المصدر حاليًا.");return;}
        String title=links.getJSONObject(0).getString("title");
        JSONObject ar=MediaResolver.readMetadata("https://ar.wikipedia.org/w/api.php?action=query&format=json&formatversion=2&redirects=1&prop=extracts&exintro=1&explaintext=1&exchars=900&titles="+Uri.encode(title),()->Thread.currentThread().isInterrupted()).getJSONObject("query").getJSONArray("pages").getJSONObject(0);
        String extract=ar.optString("extract","");
        data.put("extractAr",extract.isEmpty()?"الوصف العربي غير متوفر في المصدر حاليًا.":extract).put("titleAr",ar.optString("title",title)).put("pageidAr",ar.optLong("pageid",0));
    }
    private synchronized void saveInfo(Scene scene,JSONObject data){
        if(scene.id.startsWith("U")&&!isCustom(scene))return;
        prefs.edit().putString("info:"+scene.id,data.toString()).commit();
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
        arabic(data);
        saveInfo(scene,data);
        JSONArray coords=data.optJSONArray("coordinates");
        if(!scene.category.equals("SPACE")&&coords!=null && coords.length()>0){
            JSONObject c=coords.getJSONObject(0);
            JSONObject sun=MediaResolver.readMetadata("https://api.open-meteo.com/v1/forecast?latitude="+c.getDouble("lat")+"&longitude="+c.getDouble("lon")+"&daily=sunrise,sunset&timezone=auto&forecast_days=7",()->Thread.currentThread().isInterrupted());
            String zone=sun.getString("timezone");ZoneId.of(zone);
            data.put("zone",zone).put("daily",sun.getJSONObject("daily"));
            saveInfo(scene,data);
        }
    }
}
