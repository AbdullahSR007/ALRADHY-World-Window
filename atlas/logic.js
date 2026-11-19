
/* ===================== V47 · Satellite atlas, physical-remote first ===================== */
const V47_KEY='atlas_v47_state';
const CAT_COLORS={history:'#f59e0b',legends:'#a78bfa',cities:'#22d3ee',nature:'#4ade80',space:'#f472b6'};
const CAT_ICONS={history:'🏛️',legends:'⭐',cities:'🏙️',nature:'🌿',space:'🪐'};
const CATS=[['all','الكل'],['history','الحقب'],['legends','الرموز'],['cities','المدن'],['nature','الطبيعة'],['space','الفضاء'],['fav','★ المفضلة']];
let v47={cat:'all',lastId:null,labels:true,favs:[]};
try{Object.assign(v47,JSON.parse(localStorage.getItem(V47_KEY)||'{}'));}catch(e){}
function saveState(){try{localStorage.setItem(V47_KEY,JSON.stringify(v47));}catch(e){}}
let satLayer,labelLayer,dotLayer,activeDot,searchTimer,selectTimer,lastQuery='';
const byId=new Map(ATLAS_DATA.map(x=>[x.id,x]));
function toast(t){const el=document.getElementById('toast');el.textContent=t;el.classList.add('show');clearTimeout(toast.t);toast.t=setTimeout(()=>el.classList.remove('show'),1800);}

/* ---------- Satellite map (Esri World Imagery) — no street map, no tour ---------- */
function initMap(){
 map=L.map('map',{attributionControl:true,keyboard:false,zoomControl:true,preferCanvas:true,worldCopyJump:true,minZoom:2,maxZoom:18}).setView([24.7136,46.6753],4);
 satLayer=L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}',{maxZoom:18,maxNativeZoom:17,attribution:'صور القمر الصناعي © Esri، Maxar، Earthstar Geographics'}).addTo(map);
 labelLayer=L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Boundaries_and_Places/MapServer/tile/{z}/{y}/{x}',{maxZoom:18,maxNativeZoom:17,pane:'overlayPane',opacity:.95});
 if(v47.labels)labelLayer.addTo(map);
 document.getElementById('labelsBtn').classList.toggle('active',!!v47.labels);
 map.zoomControl.setPosition('topleft');
 dotLayer=L.layerGroup().addTo(map);
 marker=L.marker([24.7136,46.6753],{keyboard:false});
}
function toggleLabels(){v47.labels=!v47.labels;if(v47.labels)labelLayer.addTo(map);else map.removeLayer(labelLayer);document.getElementById('labelsBtn').classList.toggle('active',v47.labels);saveState();toast(v47.labels?'أسماء الأماكن ظاهرة':'صورة القمر الصناعي فقط');}
function resetView(){map.flyTo([22,30],2.6,{duration:1});}
function drawDots(){
 dotLayer.clearLayers();
 filteredList.forEach((x,i)=>{if(x.cat==='space')return;
  L.circleMarker([x.lat,x.lng],{radius:5,color:'#0b1220',weight:1,fillColor:CAT_COLORS[x.cat],fillOpacity:.9}).on('click',()=>selectNode(i)).addTo(dotLayer);});
}
function highlightDot(x){
 if(activeDot){map.removeLayer(activeDot);activeDot=null;}
 if(x.cat==='space')return;
 activeDot=L.circleMarker([x.lat,x.lng],{radius:12,color:'#fff',weight:3,fillColor:CAT_COLORS[x.cat],fillOpacity:1}).addTo(map)
  .bindTooltip(x.title,{permanent:true,direction:'top',offset:[0,-12],className:'atlas-tip'});
}
const ZOOM_BY_CAT={history:6,legends:6,cities:10,nature:8};

/* ---------- Arabic-aware search ---------- */
function norm(s){return String(s||'').toLocaleLowerCase('ar').replace(/[ً-ٰٟـ]/g,'').replace(/[أإآٱ]/g,'ا').replace(/ة/g,'ه').replace(/ى/g,'ي').replace(/ؤ/g,'و').replace(/ئ/g,'ي');}
ATLAS_DATA.forEach(x=>x._s=norm([x.title,x.period,x.region,x.desc,LABELS[x.cat],x.wiki||''].join(' ')));
function onSearch(q){clearTimeout(searchTimer);searchTimer=setTimeout(()=>applyFilter(q),250);}
function clearSearch(){const s=document.getElementById('search');s.value='';applyFilter('');}
function baseList(){if(v47.cat==='fav')return ATLAS_DATA.filter(x=>v47.favs.includes(x.id));return v47.cat==='all'?[...ATLAS_DATA]:ATLAS_DATA.filter(x=>x.cat===v47.cat);}
function applyFilter(q,keepId){
 lastQuery=q=(q||'').trim();
 const words=norm(q).split(/\s+/).filter(Boolean);
 filteredList=baseList().filter(x=>words.every(w=>x._s.includes(w)));
 if(words.length){const rank=x=>{const t=norm(x.title),r=norm(x.region);return words.every(w=>t.includes(w))?0:words.every(w=>r.includes(w))?1:2;};filteredList=filteredList.map((x,i)=>[x,rank(x),i]).sort((a,b)=>a[1]-b[1]||a[2]-b[2]).map(a=>a[0]);}
 document.getElementById('count').textContent='('+filteredList.length+')';
 document.getElementById('searchClear').style.visibility=q?'visible':'hidden';
 drawDots();renderTimeline();
 if(!filteredList.length){renderEmpty();if(activeDot){map.removeLayer(activeDot);activeDot=null;}return;}
 const idx=keepId?Math.max(0,filteredList.findIndex(x=>x.id===keepId)):0;
 selectNode(idx);
}
function renderTabs(){
 const box=document.getElementById('tabs');box.innerHTML='';
 CATS.forEach(([k,label])=>{const n=k==='all'?ATLAS_DATA.length:k==='fav'?v47.favs.length:ATLAS_DATA.filter(x=>x.cat===k).length;
  const b=document.createElement('button');b.dataset.cat=k;b.className=v47.cat===k?'active':'';
  b.innerHTML=(CAT_ICONS[k]?CAT_ICONS[k]+' ':'')+label+'<span class="n">'+n+'</span>';
  b.onclick=()=>filterCategory(k);box.appendChild(b);});
}
function filterCategory(cat){if(cat==='fav'&&!v47.favs.length){toast('لا توجد محطات مفضلة بعد — اضغط ☆ لإضافة محطة');return;}v47.cat=cat;saveState();renderTabs();applyFilter(document.getElementById('search').value);}

/* ---------- Timeline ---------- */
function renderTimeline(){
 const bar=document.getElementById('timeline'),key=filteredList.map(x=>x.id).join(',')+'|'+v47.favs.join(',');
 if(bar.dataset.catalogKey!==key){
  const frag=document.createDocumentFragment();
  filteredList.forEach((x,i)=>{const b=document.createElement('button');b.className='card';b.dataset.i=i;
   b.innerHTML='<small>'+(CAT_ICONS[x.cat]||'')+' #'+x.id+' · '+x.period+(v47.favs.includes(x.id)?' <span class="star">★</span>':'')+'</small><br><strong>'+x.title+'</strong><br><small>📍 '+x.region+'</small>';
   b.onclick=()=>selectNode(i);frag.appendChild(b);});
  bar.replaceChildren(frag);bar.dataset.catalogKey=key;
 }
 bar.querySelectorAll('.card').forEach((b,i)=>b.classList.toggle('active',i===currentIndex));
}

/* ---------- Enriched information ---------- */
function yearsAgo(period){
 const m=String(period).match(/(\d[\d,]*)\s*(ق\.?\s*م|م)?/);if(!m)return '';
 let y=+m[1].replace(/,/g,'');if(/ق/.test(m[2]||''))y=-y;
 const ago=new Date().getFullYear()-y;if(ago<=0||ago>5e6)return '';
 return ago>=1000?'قبل نحو '+Math.round(ago/100)/10+' ألف سنة':'قبل نحو '+ago+' سنة';
}
function fmtCoord(v,pos,neg){return Math.abs(v).toFixed(2)+'° '+(v>=0?pos:neg);}
function km(a,b){const R=6371,r=Math.PI/180,dl=(b.lat-a.lat)*r,dn=(b.lng-a.lng)*r,h=Math.sin(dl/2)**2+Math.cos(a.lat*r)*Math.cos(b.lat*r)*Math.sin(dn/2)**2;return 2*R*Math.asin(Math.sqrt(h));}
const RIYADH={lat:24.7136,lng:46.6753};
function renderFacts(x){
 const rows=[['التصنيف',(CAT_ICONS[x.cat]||'')+' '+LABELS[x.cat]],['الزمن',x.period+(yearsAgo(x.period)?' — '+yearsAgo(x.period):'')],['الموقع',x.region]];
 if(x.cat!=='space'){rows.push(['الإحداثيات',fmtCoord(x.lat,'ش','ج')+' ، '+fmtCoord(x.lng,'شر','غ')]);rows.push(['المسافة من الرياض',Math.round(km(RIYADH,x)).toLocaleString('ar-SA')+' كم']);}
 rows.push(['رقم المحطة','#'+x.id+' من '+ATLAS_DATA.length]);
 document.getElementById('facts').innerHTML=rows.map(r=>'<dt>'+r[0]+'</dt><dd>'+escapeSVG(r[1])+'</dd>').join('');
 const near=document.getElementById('nearby'),sec=document.getElementById('nearSec');near.innerHTML='';
 if(x.cat==='space'){sec.style.display='none';return;}sec.style.display='';
 ATLAS_DATA.filter(y=>y.id!==x.id&&y.cat!=='space').map(y=>[y,km(x,y)]).sort((a,b)=>a[1]-b[1]).slice(0,4).forEach(([y,d])=>{
  const b=document.createElement('button');b.innerHTML='<span>'+(CAT_ICONS[y.cat]||'')+' '+escapeSVG(y.title)+'</span><small>'+Math.round(d).toLocaleString('ar-SA')+' كم</small>';
  b.onclick=()=>jumpTo(y.id);near.appendChild(b);});
}
function jumpTo(id){let i=filteredList.findIndex(x=>x.id===id);if(i<0){v47.cat='all';renderTabs();document.getElementById('search').value='';applyFilter('',id);return;}selectNode(i);}
const WIKI_CACHE_KEY='atlas_v47_wiki';let WIKI={};try{WIKI=JSON.parse(localStorage.getItem(WIKI_CACHE_KEY)||'{}');}catch(e){}
function enTitle(x){return x.wiki||WIKI_PAGE_TITLES[x.id-1]||'';}
async function getJSON(u){const r=await fetch(u);if(!r.ok)throw Error(r.status);return r.json();}
function firstPage(j){const p=j&&j.query&&j.query.pages;return p?Object.values(p)[0]:null;}
async function loadWiki(x){
 const box=document.getElementById('wikiText');
 if(WIKI[x.id]){box.className='';box.textContent=WIKI[x.id].t;return;}
 box.className='loading';box.textContent='جارٍ جلب نبذة موسّعة من ويكيبيديا…';
 const id=x.id;
 try{
  let ar='',en='',arTitle='';const t=enTitle(x);
  if(t){const p=firstPage(await getJSON('https://en.wikipedia.org/w/api.php?action=query&format=json&origin=*&redirects=1&prop=langlinks|extracts&lllang=ar&exintro=1&explaintext=1&exsentences=5&titles='+encodeURIComponent(t)));
   if(p){en=p.extract||'';arTitle=p.langlinks&&p.langlinks[0]&&p.langlinks[0]['*']||'';}}
  if(!arTitle){const s=await getJSON('https://ar.wikipedia.org/w/api.php?action=query&format=json&origin=*&list=search&srlimit=1&srsearch='+encodeURIComponent(x.title));arTitle=s.query&&s.query.search&&s.query.search[0]&&s.query.search[0].title||'';}
  if(arTitle){const p=firstPage(await getJSON('https://ar.wikipedia.org/w/api.php?action=query&format=json&origin=*&redirects=1&prop=extracts&exintro=1&explaintext=1&titles='+encodeURIComponent(arTitle)));ar=p&&p.extract||'';}
  let text=ar.trim();if(text.length>900)text=text.slice(0,900).replace(/\s\S*$/,'')+'…';
  if(!text&&en)text='(بالإنجليزية) '+en.trim();
  if(!text)throw Error('empty');
  WIKI[id]={t:text,ar:arTitle};try{localStorage.setItem(WIKI_CACHE_KEY,JSON.stringify(WIKI));}catch(e){}
  if(filteredList[currentIndex]&&filteredList[currentIndex].id===id){box.className='';box.textContent=text;}
 }catch(e){if(filteredList[currentIndex]&&filteredList[currentIndex].id===id){box.className='loading';box.textContent=navigator.onLine===false?'النبذة الموسّعة تحتاج اتصالًا بالإنترنت — الوصف الأساسي أعلاه متاح دائمًا.':'تعذر جلب نبذة إضافية لهذه المحطة الآن.';}}
}
function openArticle(){const x=filteredList[currentIndex];if(!x)return;const t=enTitle(x)||x.title;if(window.AndroidBridge&&AndroidBridge.openWikiPage)AndroidBridge.openWikiPage(t);}

/* ---------- Favourites ---------- */
function updateFavBtn(x){const on=v47.favs.includes(x.id),b=document.getElementById('favBtn');b.classList.toggle('on',on);b.textContent=on?'★ مفضلة':'☆ مفضلة';}
function toggleFav(){const x=filteredList[currentIndex];if(!x)return;const i=v47.favs.indexOf(x.id);if(i>=0)v47.favs.splice(i,1);else v47.favs.push(x.id);saveState();updateFavBtn(x);renderTabs();renderTimeline();toast(i>=0?'أُزيلت من المفضلة':'أُضيفت إلى المفضلة ★');}

/* ---------- Station selection ---------- */
function selectNode(i){
 if(!filteredList.length)return renderEmpty();
 currentIndex=(i+filteredList.length)%filteredList.length;photoIndex=0;
 const x=filteredList[currentIndex];v47.lastId=x.id;saveState();
 document.getElementById('period').textContent=LABELS[x.cat]+' · '+x.period;
 document.getElementById('index').textContent='#'+(currentIndex+1)+' / '+filteredList.length;
 document.getElementById('title').textContent=x.title;
 document.getElementById('region').textContent='📍 '+x.region;
 document.getElementById('desc').textContent=x.desc;
 renderStationGallery(x);renderFacts(x);updateFavBtn(x);
 const sc=document.getElementById('spaceCard');
 if(x.cat==='space'){sc.classList.add('visible');document.getElementById('spaceTitle').textContent=CAT_ICONS.space+' '+x.title;document.getElementById('spaceText').textContent=x.desc;highlightDot(x);}
 else{sc.classList.remove('visible');map.flyTo([x.lat,x.lng],Math.max(map.getZoom(),ZOOM_BY_CAT[x.cat]||6),{duration:1.1});highlightDot(x);}
 renderTimeline();
 const card=document.querySelector('#timeline .card.active');if(card&&document.activeElement!==card)card.scrollIntoView({behavior:'smooth',inline:'center',block:'nearest'});
 document.getElementById('panel').scrollTop=0;
 fetchStationGallery(x,false);
 clearTimeout(loadWiki.t);loadWiki.t=setTimeout(()=>loadWiki(x),400);
}
function speakCurrent(){
 if(!filteredList.length)return;const x=filteredList[currentIndex];
 const extra=WIKI[x.id]&&!/^\(بالإنجليزية\)/.test(WIKI[x.id].t)?' '+WIKI[x.id].t:'';
 const text=x.title+'. الموقع: '+x.region+'. '+x.desc+'.'+extra;
 if(window.AndroidBridge&&AndroidBridge.speakArabic)AndroidBridge.speakArabic(text,false);
 else if('speechSynthesis'in window){speechSynthesis.cancel();const u=new SpeechSynthesisUtterance(text);u.lang='ar-SA';speechSynthesis.speak(u);}
 toast('🎙 قراءة — اضغط ⏯ للإيقاف');speakCurrent.on=true;
}
function stopSpeech(){speakCurrent.on=false;if(window.AndroidBridge&&AndroidBridge.stopSpeech)AndroidBridge.stopSpeech();if(window.speechSynthesis)speechSynthesis.cancel();}
function nativeTtsDone(){speakCurrent.on=false;}
function toggleTour(){}

/* ---------- Physical remote (D-pad / OK / Back / media keys) ---------- */
const FOCUSABLE='#top button,#search,#panel button,#hero,#map,#timeline .card,.leaflet-control-zoom a';
function visible(el){if(!el.isConnected)return false;const s=getComputedStyle(el);if(s.visibility==='hidden'||s.display==='none')return false;const r=el.getBoundingClientRect();return r.width>0&&r.height>0&&!el.closest('[style*="display: none"]');}
function scrollParentOf(el){return el.closest('#panel,#timeline,#tabs');}
function moveFocus(dir){
 const cur=document.activeElement&&document.activeElement!==document.body?document.activeElement:null;
 if(!cur){focusDefault();return true;}
 const c=cur.getBoundingClientRect();
 const panel=document.getElementById('panel').getBoundingClientRect(),tl=document.getElementById('timeline').getBoundingClientRect();
 let best=null,bestScore=1e9;
 document.querySelectorAll(FOCUSABLE).forEach(el=>{
  if(el===cur||!visible(el))return;
  let r=el.getBoundingClientRect();
  // ignore items scrolled far outside their own scroll area, unless they are the next in line inside the same container
  const sp=scrollParentOf(el);
  if(sp&&sp!==scrollParentOf(cur)){const pr=sp.getBoundingClientRect();if(r.bottom<pr.top||r.top>pr.bottom||r.right<pr.left||r.left>pr.right)return;}
  let main,ortho;
  if(dir==='down'){if(r.top<c.bottom-4)return;main=r.top-c.bottom;ortho=Math.max(0,r.left-c.right,c.left-r.right);}
  else if(dir==='up'){if(r.bottom>c.top+4)return;main=c.top-r.bottom;ortho=Math.max(0,r.left-c.right,c.left-r.right);}
  else if(dir==='left'){if(r.right>c.left+4)return;main=c.left-r.right;ortho=Math.max(0,r.top-c.bottom,c.top-r.bottom);}
  else{if(r.left<c.right-4)return;main=r.left-c.right;ortho=Math.max(0,r.top-c.bottom,c.top-r.bottom);}
  const score=Math.max(0,main)+ortho*4+(scrollParentOf(el)!==scrollParentOf(cur)&&scrollParentOf(cur)&&scrollParentOf(cur).id==='panel'?5000:0);
  if(score<bestScore){bestScore=score;best=el;}
 });
 if(best){best.focus({preventScroll:true});best.scrollIntoView({block:'nearest',inline:'nearest',behavior:'smooth'});return true;}
 return false;
}
function focusDefault(){const a=document.querySelector('#timeline .card.active')||document.querySelector('#timeline .card')||document.querySelector('#tabs button');a&&a.focus();a&&a.scrollIntoView({block:'nearest',inline:'center'});}
const PAN=180;let mapMode=false;
function setMapMode(on){mapMode=on;const h=document.getElementById('mapHint');document.getElementById('map').classList.toggle('exploring',on);
 h.textContent=on?'وضع استكشاف الخريطة: الأسهم للتحريك · OK للتقريب · رجوع للخروج':'اضغط OK لاستكشاف الخريطة بالأسهم';h.classList.toggle('on',on);if(on)toast('🛰️ وضع استكشاف الخريطة');}
function onRemoteKey(e){
 const k=e.key,el=document.activeElement,ov=document.getElementById('overlay').classList.contains('visible');
 const dir={ArrowUp:'up',ArrowDown:'down',ArrowLeft:'left',ArrowRight:'right'}[k];
 document.body.classList.add('kbd');
 // media / colour keys work everywhere
 if(k==='MediaPlayPause'||k==='MediaPlay'||k==='MediaPause'){e.preventDefault();speakCurrent.on?stopSpeech():speakCurrent();return;}
 if(k==='MediaFastForward'||k==='MediaTrackNext'||k==='ChannelDown'||k==='PageDown'){e.preventDefault();nextNode();return;}
 if(k==='MediaRewind'||k==='MediaTrackPrevious'||k==='ChannelUp'||k==='PageUp'){e.preventDefault();prevNode();return;}
 if(/^[1-7]$/.test(k)&&el&&el.id!=='search'){e.preventDefault();filterCategory(CATS[+k-1][0]);return;}
 if(ov){ // full-screen photo: left/right flip photos, OK/Back close
  if(k==='ArrowLeft'){e.preventDefault();nextPhoto();return;}
  if(k==='ArrowRight'){e.preventDefault();prevPhoto();return;}
  if(k==='Enter'||k==='Escape'||k==='Backspace'){e.preventDefault();closeOverlay();return;}
  e.preventDefault();return;
 }
 if(el&&el.id==='map'){
  if(!mapMode&&k==='Enter'){e.preventDefault();setMapMode(true);return;}
  if(mapMode){ // explore mode: arrows pan, OK zooms in, Back leaves
   if(dir){e.preventDefault();const d={up:[0,-PAN],down:[0,PAN],left:[-PAN,0],right:[PAN,0]}[dir];map.panBy(d,{animate:true});return;}
   if(k==='Enter'){e.preventDefault();map.zoomIn();return;}
  }
  if(k==='+'||k==='='){map.zoomIn();return;}if(k==='-'){map.zoomOut();return;}
 }
 if(el&&el.id==='search'){
  if(k==='ArrowUp'||k==='ArrowDown'){e.preventDefault();moveFocus(dir);return;}
  if(k==='Enter'){e.preventDefault();el.blur();focusDefault();return;}
  return; // left/right move the text cursor
 }
 if(dir){e.preventDefault();if(!moveFocus(dir)&&el&&el.classList.contains('card')){dir==='left'?nextNode():dir==='right'?prevNode():0;}return;}
 if(k==='Enter'&&el&&el.id==='hero'){e.preventDefault();openOverlay();return;}
 if(k==='Escape'||k==='Backspace'){if(handleNativeBack())e.preventDefault();}
}
function handleNativeBack(){
 const o=document.getElementById('overlay');if(o.classList.contains('visible')){closeOverlay();return true;}
 if(speakCurrent.on){stopSpeech();return true;}
 const el=document.activeElement;
 if(el&&el.id==='search'){el.blur();focusDefault();return true;}
 if(lastQuery){clearSearch();focusDefault();toast('تم مسح البحث');return true;}
 if(mapMode){setMapMode(false);if(map.getZoom()>(ZOOM_BY_CAT[(filteredList[currentIndex]||{}).cat]||6)+2)map.zoomOut(2);return true;}
 if(el&&(el.id==='map'||el.closest('#panel')||el.closest('#top'))){focusDefault();return true;}
 return false;
}
function openOverlay(){document.getElementById('overlay').classList.add('visible');}
function closeOverlay(){document.getElementById('overlay').classList.remove('visible');setTimeout(()=>{map.invalidateSize();document.getElementById('hero').focus();},100);}
// selecting a station by just landing on its card (debounced so fast scrolling stays smooth)
document.addEventListener('focusin',e=>{if(mapMode&&e.target.id!=='map')setMapMode(false);const c=e.target.closest&&e.target.closest('#timeline .card');if(c){clearTimeout(selectTimer);const i=+c.dataset.i;if(i!==currentIndex)selectTimer=setTimeout(()=>selectNode(i),450);}});
document.addEventListener('keydown',onRemoteKey,true);
document.addEventListener('mousedown',()=>document.body.classList.remove('kbd'));

window.addEventListener('load',()=>{
 initMap();renderTabs();
 if(v47.cat==='fav'&&!v47.favs.length)v47.cat='all';
 renderTabs();applyFilter('',v47.lastId);
 setTimeout(focusDefault,300);setMapMode(false);
 window.addEventListener('resize',()=>map.invalidateSize());
});
</script></body></html>
