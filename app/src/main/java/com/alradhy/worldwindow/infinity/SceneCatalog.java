package com.alradhy.worldwindow.infinity;
import java.util.*;
/** Curated, distinct destinations. Discovery resolves legally reusable assets, not dead hotlinks. */
public final class SceneCatalog {
    private SceneCatalog(){}
    public static final String[] CATEGORIES={"ALL","CITIES","NATURE","OCEANS","SPACE","MY WORLD"};
    public static List<Scene> create(){
        ArrayList<Scene> scenes=new ArrayList<>();
        add(scenes,"CITIES","COMMONS",CITIES_DATA);
        add(scenes,"NATURE","COMMONS",NATURE_DATA);
        add(scenes,"OCEANS","COMMONS",OCEANS_DATA);
        add(scenes,"SPACE","NASA",SPACE_DATA);
        return scenes;
    }
    private static void add(List<Scene> list,String cat,String provider,String raw){
        int n=0;
        for(String line:raw.strip().split("\\R")){
            if(line.isBlank())continue;
            String[] p=line.split("\\|",-1);
            if(p.length!=6)throw new IllegalStateException("Catalog entry: "+line);
            list.add(new Scene(cat.substring(0,1)+(++n),cat,p[0],p[1],p[2],p[3],p[4],provider));
        }
    }
    private static final String CITIES_DATA = """
Tokyo|طوكيو|Neon streets • Japan|Asia/Tokyo|Tokyo night skyline panorama|Japan
Kyoto|كيوتو|Temples • Japan|Asia/Tokyo|Kyoto Japan Kiyomizu temple landscape|Japan
Seoul|سيول|City lights • Korea|Asia/Seoul|Seoul city skyline night panorama|Korea
Singapore|سنغافورة|Marina Bay • Singapore|Asia/Singapore|Singapore Marina Bay night skyline|Singapore
Hong Kong|هونغ كونغ|Victoria Harbour • China|Asia/Hong_Kong|Hong Kong Victoria Harbour night skyline|China
Shanghai|شنغهاي|The Bund • China|Asia/Shanghai|Shanghai Bund skyline night|China
Dubai|دبي|City of light • UAE|Asia/Dubai|Dubai skyline Burj Khalifa night|UAE
Riyadh|الرياض|Saudi Arabia • Kingdom|Asia/Riyadh|Riyadh skyline Kingdom Centre night|Saudi Arabia
Makkah|مكة المكرمة|Holy city • Saudi Arabia|Asia/Riyadh|Mecca Masjid al Haram aerial|Saudi Arabia
Madinah|المدينة المنورة|Prophet's Mosque • Saudi Arabia|Asia/Riyadh|Medina Prophet Mosque panorama|Saudi Arabia
Istanbul|إسطنبول|Bosporus • Türkiye|Europe/Istanbul|Istanbul Bosphorus skyline sunset|Türkiye
Cappadocia|كابادوكيا|Valleys • Türkiye|Europe/Istanbul|Cappadocia hot air balloons landscape|Türkiye
London|لندن|Thames • England|Europe/London|London Tower Bridge evening skyline|United Kingdom
Paris|باريس|The Seine • France|Europe/Paris|Paris Seine Eiffel Tower night panorama|France
Rome|روما|Ancient capital • Italy|Europe/Rome|Rome Colosseum dusk panoramic|Italy
Venice|البندقية|Canals • Italy|Europe/Rome|Venice Grand Canal sunset panoramic|Italy
Barcelona|برشلونة|Mediterranean • Spain|Europe/Madrid|Barcelona skyline Sagrada Familia|Spain
Prague|براغ|Old town • Czechia|Europe/Prague|Prague Charles Bridge sunrise|Czechia
Vienna|فيينا|Imperial city • Austria|Europe/Vienna|Vienna city historic center night|Austria
Amsterdam|أمستردام|Canals • Netherlands|Europe/Amsterdam|Amsterdam canal houses twilight|Netherlands
New York|نيويورك|Manhattan • USA|America/New_York|New York City Manhattan night skyline|USA
San Francisco|سان فرانسيسكو|Golden Gate • USA|America/Los_Angeles|San Francisco Golden Gate Bridge sunset|USA
Rio de Janeiro|ريو دي جانيرو|Sugarloaf • Brazil|America/Sao_Paulo|Rio de Janeiro sugarloaf panorama|Brazil
Cape Town|كيب تاون|Table Mountain • South Africa|Africa/Johannesburg|Cape Town Table Mountain aerial|South Africa
Marrakesh|مراكش|Medina • Morocco|Africa/Casablanca|Marrakech medina square sunset|Morocco
        """;

    private static final String NATURE_DATA = """
Aurora Borealis|الشفق القطبي|Northern skies|Europe/Oslo|aurora borealis norway night sky|Norway
Iceland Falls|شلالات آيسلندا|Water and volcanic rock|Atlantic/Reykjavik|Skogafoss Iceland waterfall landscape|Iceland
Swiss Alps|جبال الألب|High mountain light|Europe/Zurich|Swiss Alps Matterhorn landscape|Switzerland
Norwegian Fjords|المضايق النرويجية|Deep blue valleys|Europe/Oslo|Geirangerfjord panorama fjord|Norway
Grand Canyon|الأخدود العظيم|Ancient earth|America/Phoenix|Grand Canyon panorama landscape|USA
Yosemite|يوسيميتي|Granite and forest|America/Los_Angeles|Yosemite Valley tunnel view|USA
Banff Lakes|بحيرات بانف|Turquoise mountain lakes|America/Edmonton|Moraine Lake Banff landscape|Canada
Yellowstone|يلوستون|Geothermal wonders|America/Denver|Yellowstone Grand Prismatic Spring aerial|USA
Amazon Rainforest|غابات الأمازون|Green world|America/Manaus|Amazon rainforest aerial river|Brazil
Patagonia|باتاغونيا|World's southern edge|America/Argentina/Rio_Gallegos|Torres del Paine Patagonia landscape|Chile
Sahara Dunes|كثبان الصحراء|Silence and sand|Africa/Algiers|Sahara desert dunes sunset|Algeria
Namib Desert|صحراء ناميب|Red dunes|Africa/Windhoek|Sossusvlei Namib red dunes|Namibia
Victoria Falls|شلالات فيكتوريا|Great Zambezi|Africa/Harare|Victoria Falls aerial panorama|Zimbabwe
Kilimanjaro|كليمنجارو|Above the clouds|Africa/Dar_es_Salaam|Mount Kilimanjaro landscape|Tanzania
Serengeti|سيرينغيتي|Savanna horizons|Africa/Dar_es_Salaam|Serengeti plains sunrise landscape|Tanzania
Mount Fuji|جبل فوجي|Perfect volcano|Asia/Tokyo|Mount Fuji lake landscape reflection|Japan
Bali Rice Terraces|مدرجات بالي|Green terraces|Asia/Makassar|Bali Tegallalang rice terrace panorama|Indonesia
Ha Long Bay|خليج ها لونغ|Limestone islands|Asia/Ho_Chi_Minh|Ha Long Bay limestone islands aerial|Vietnam
Himalayas|الهيمالايا|Roof of the world|Asia/Kathmandu|Himalaya mountain panorama snow|Nepal
Gobi Desert|صحراء غوبي|Mongolian steppe|Asia/Ulaanbaatar|Gobi desert Mongolia landscape|Mongolia
Wadi Rum|وادي رم|Desert monuments|Asia/Amman|Wadi Rum Jordan sandstone desert|Jordan
AlUla|العلا|Saudi sandstone wonders|Asia/Riyadh|Al Ula Elephant Rock Saudi Arabia|Saudi Arabia
Farasan Islands|جزر فرسان|Red Sea archipelago|Asia/Riyadh|Farasan Islands Saudi Arabia beach|Saudi Arabia
New Zealand Lakes|بحيرات نيوزيلندا|Southern alpine light|Pacific/Auckland|Lake Pukaki Mount Cook panorama|New Zealand
Antarctic Ice|جليد أنتاركتيكا|Frozen wilderness|Antarctica/McMurdo|Antarctica iceberg sea landscape|Antarctica
        """;

    private static final String OCEANS_DATA = """
Great Barrier Reef|الحاجز المرجاني|Coral gardens|Australia/Brisbane|Great Barrier Reef underwater coral|Australia
Red Sea Reefs|شعاب البحر الأحمر|Marine colours|Asia/Riyadh|Red Sea coral reef underwater|Saudi Arabia
Maldives|المالديف|Atolls of blue|Indian/Maldives|Maldives aerial lagoon islands|Maldives
Bora Bora|بورا بورا|Pacific lagoon|Pacific/Tahiti|Bora Bora lagoon aerial|French Polynesia
Palau|بالاو|Rock islands|Pacific/Palau|Palau rock islands lagoon aerial|Palau
Raja Ampat|راجا أمبات|Archipelago reefs|Asia/Jayapura|Raja Ampat underwater coral|Indonesia
Komodo Islands|جزر كومودو|Indonesian currents|Asia/Makassar|Komodo islands ocean aerial|Indonesia
Galápagos|غالاباغوس|Pacific wildlife|Pacific/Galapagos|Galapagos underwater sea turtle|Ecuador
Hawaii Coast|ساحل هاواي|Volcanic Pacific|Pacific/Honolulu|Na Pali coast Hawaii aerial|USA
Pacific Waves|أمواج المحيط|Ocean energy|Pacific/Honolulu|ocean waves aerial turquoise sea|Pacific
Ocean Jellyfish|قناديل البحر|Underwater ballet|Etc/UTC|jellyfish underwater aquarium ocean|Ocean
Sea Turtles|السلاحف البحرية|Blue migration|Etc/UTC|sea turtle swimming underwater coral|Ocean
Dolphin Pods|أسراب الدلافين|Ocean intelligence|Etc/UTC|dolphins underwater ocean|Ocean
Humpback Whales|الحيتان الحدباء|Giants beneath waves|Etc/UTC|humpback whale ocean underwater|Ocean
Manta Rays|أسماك المانتا|Graceful wings|Etc/UTC|manta rays underwater ocean|Ocean
Kelp Forests|غابات عشب البحر|Underwater canopy|America/Los_Angeles|kelp forest underwater California|USA
Blue Caves|الكهوف الزرقاء|Coastal mystery|Europe/Athens|blue cave Zakynthos sea|Greece
Seychelles|سيشل|Granite beaches|Indian/Mahe|Seychelles La Digue beach aerial|Seychelles
Zanzibar|زنجبار|Indian Ocean reefs|Africa/Dar_es_Salaam|Zanzibar turquoise coast aerial|Tanzania
Norwegian Sea|البحر النرويجي|Arctic water|Europe/Oslo|Lofoten islands ocean aerial|Norway
Deep Sea|أعماق المحيط|Unknown blue|Etc/UTC|deep sea bioluminescence ocean|Ocean
Coral Triangle|المثلث المرجاني|Tropical biodiversity|Asia/Makassar|coral triangle underwater fish reef|Indonesia
Ocean Sunset|غروب المحيط|Golden horizon|Etc/UTC|ocean sunset horizon seascape|Ocean
Sea Cliffs|المنحدرات البحرية|Stone meets water|Europe/Dublin|Cliffs of Moher sea aerial|Ireland
Iceberg Ocean|محيط الجليد|Blue polar ice|Atlantic/Reykjavik|iceberg floating ocean blue|Iceland
        """;

    private static final String SPACE_DATA = """
Earth from Orbit|الأرض من المدار|The blue planet|Etc/UTC|Earth from International Space Station|NASA
Earth at Night|الأرض ليلًا|City lights from space|Etc/UTC|Earth city lights timelapse ISS|NASA
Aurora from ISS|الشفق من الفضاء|Polar ribbons|Etc/UTC|aurora time lapse international space station|NASA
Earth Sunrise|شروق الأرض|Orbital dawn|Etc/UTC|Earth sunrise ISS timelapse|NASA
Cloud Systems|الغيوم من الفضاء|Planetary atmosphere|Etc/UTC|Earth clouds hurricane timelapse|NASA
Moon Surface|سطح القمر|Our nearest world|Etc/UTC|moon surface lunar mission|NASA
Apollo Missions|رحلات أبولو|Humanity's first steps|Etc/UTC|Apollo moon landing footage|NASA
Mars Landscapes|تضاريس المريخ|The red planet|Etc/UTC|Mars rover surface panorama|NASA
Perseverance|بيرسيفيرانس|Exploring Jezero|Etc/UTC|Perseverance rover Mars landing|NASA
Curiosity Rover|كيوريوسيتي|Roving Mars|Etc/UTC|Curiosity Mars rover video|NASA
Jupiter|المشتري|Gas giant|Etc/UTC|Jupiter Juno spacecraft animation|NASA
Saturn Rings|حلقات زحل|Icy rings|Etc/UTC|Saturn rings Cassini spacecraft|NASA
Solar Flares|التوهجات الشمسية|Our dynamic star|Etc/UTC|solar flare NASA SDO video|NASA
Solar Eclipse|كسوف الشمس|Shadow across Earth|Etc/UTC|total solar eclipse NASA footage|NASA
Milky Way|درب التبانة|Our galaxy|Etc/UTC|Milky Way galaxy animation NASA|NASA
Nebulae|السدم|Stellar nurseries|Etc/UTC|nebula space Hubble NASA animation|NASA
James Webb|جيمس ويب|Deepest infrared views|Etc/UTC|James Webb telescope image video NASA|NASA
Hubble Universe|كون هابل|Cosmic portraits|Etc/UTC|Hubble telescope galaxies zoom|NASA
Star Clusters|العناقيد النجمية|Galactic sparks|Etc/UTC|star clusters NASA Hubble visualization|NASA
Black Holes|الثقوب السوداء|Gravity unleashed|Etc/UTC|black hole NASA visualization|NASA
Galactic Collision|تصادم المجرات|Cosmic motion|Etc/UTC|colliding galaxies NASA visualization|NASA
Artemis|أرتميس|Return to the moon|Etc/UTC|Artemis mission moon rocket launch NASA|NASA
Rocket Launch|إطلاق الصواريخ|Powered ascent|Etc/UTC|NASA rocket launch high resolution|NASA
International Space Station|محطة الفضاء الدولية|Living in orbit|Etc/UTC|ISS tour astronauts NASA|NASA
Voyager|فوياجر|Journey beyond planets|Etc/UTC|Voyager spacecraft NASA animation|NASA
        """;
}
