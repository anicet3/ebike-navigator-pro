package com.ebike.navigator

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.text.InputType
import android.view.*
import android.widget.*
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.*
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.layers.*
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.geojson.*
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.Executors
import kotlin.math.*

class MainActivity:Activity(){
 private lateinit var mapView:MapView
 private var map:MapLibreMap?=null
 private lateinit var status:TextView
 private lateinit var metrics:TextView
 private lateinit var search:EditText
 private val ui=Handler(Looper.getMainLooper())
 private val io=Executors.newSingleThreadExecutor()
 private val prefs by lazy{getSharedPreferences("profile",MODE_PRIVATE)}
 private var chosen:Route?=null
 private var dest:Coord?=null
 private var follow=true
 private var satellite=false
 private var busy=false
 private var ready=false
 private var rendered:Route?=null
 private var firstFix=true
 private var requestId=0
 private val normalStyle="https://tiles.openfreemap.org/styles/liberty"
 private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
 private fun text(s:String,size:Float=16f)=TextView(this).apply{text=s;textSize=size;setTextColor(0xffe5f5ed.toInt());setPadding(dp(8),dp(4),dp(8),dp(4))}
 private fun button(s:String,f:()->Unit)=Button(this).apply{text=s;isAllCaps=false;setOnClickListener{f()}}
 private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_LONG).show()
 private fun row(root:LinearLayout,vararg buttons:Button){val r=LinearLayout(this);for(b in buttons)r.addView(b,LinearLayout.LayoutParams(0,dp(48),1f));root.addView(r)}
 override fun onCreate(b:Bundle?){
 super.onCreate(b);MapLibre.getInstance(this);Session.key=prefs.getString("ors","").orEmpty();Session.voice=prefs.getBoolean("voice",true)
 val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(0xff101816.toInt())};setContentView(root)
 if(Build.VERSION.SDK_INT>=35)root.setOnApplyWindowInsetsListener{v,i->val bars=i.getInsets(WindowInsets.Type.systemBars());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);i}
 root.addView(text("E-BIKE NAVIGATOR PRO • SAMEBIKE",19f));status=text("GPS en attente",18f);root.addView(status)
 val sr=LinearLayout(this);search=EditText(this).apply{hint="Adresse ou école";isSingleLine=true};sr.addView(search,LinearLayout.LayoutParams(0,dp(52),1f));sr.addView(button("Chercher"){searchAddress()});root.addView(sr)
 mapView=MapView(this);mapView.onCreate(b);root.addView(mapView,LinearLayout.LayoutParams(-1,0,1f))
 row(root,button("Recentrer"){follow=true;recenter()},button("Satellite"){toggleSatellite()},button("Profil"){settings()})
 row(root,button("Tester ORS"){testOrs()},button("Répéter"){if(Session.active)startService(Intent(this,NavigationService::class.java).setAction("REPEAT"))else toast("Démarrez le guidage")},button("Voix"){Session.voice=!Session.voice;prefs.edit().putBoolean("voice",Session.voice).apply();toast(if(Session.voice)"Voix activée" else "Voix coupée")})
 metrics=text("Samebike • 15 Ah",15f);root.addView(metrics)
 row(root,button("Démarrer"){startNav()},button("Arrêter"){stopNav()},button("GPS off"){stopNav();stopService(Intent(this,NavigationService::class.java));firstFix=true})
 mapView.getMapAsync{m->map=m;m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(45.5019,-73.5674),12.0));m.addOnCameraMoveStartedListener{reason->if(reason==MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE)follow=false};m.addOnMapLongClickListener{p->if(!Session.active)confirmDestination("Point choisi",Coord(p.latitude,p.longitude));true};loadStyle()}
 ensureGps()
 }
 private fun ensureGps(){if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION),10)}else{if(!Session.gpsOn)startForegroundService(Intent(this,NavigationService::class.java));if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),11)}}
 override fun onRequestPermissionsResult(r:Int,p:Array<out String>,g:IntArray){super.onRequestPermissionsResult(r,p,g);if(r==10&&checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED)ensureGps()}
 private fun loadStyle(){ready=false;val m=map?:return
 val builder=if(!satellite)Style.Builder().fromUri(normalStyle)else{
 val source=JSONObject().put("type","raster").put("tiles",JSONArray().put(prefs.getString("satellite",""))).put("tileSize",256).put("attribution",prefs.getString("attribution",""))
 val json=JSONObject().put("version",8).put("sources",JSONObject().put("sat",source)).put("layers",JSONArray().put(JSONObject().put("id","sat").put("type","raster").put("source","sat")))
 Style.Builder().fromJson(json.toString())}
 m.setStyle(builder){s->
 s.addSource(GeoJsonSource("route-data",FeatureCollection.fromFeatures(emptyArray<Feature>())))
 s.addLayer(LineLayer("route-outline","route-data").withProperties(lineColor("#FFFFFF"),lineWidth(9f)))
 s.addLayer(LineLayer("route-line","route-data").withProperties(lineColor("#087F57"),lineWidth(6f),lineCap(Property.LINE_CAP_ROUND),lineJoin(Property.LINE_JOIN_ROUND)))
 s.addSource(GeoJsonSource("gps-data",FeatureCollection.fromFeatures(emptyArray<Feature>())))
 s.addLayer(CircleLayer("gps-dot","gps-data").withProperties(circleColor("#248BFF"),circleRadius(9f),circleStrokeColor("#FFFFFF"),circleStrokeWidth(3f)))
 ready=true;rendered=null;drawRoute(Session.route?:chosen)}
 }
 private fun toggleSatellite(){if(!satellite&&prefs.getString("satellite","").isNullOrBlank()){toast("Configurez un fournisseur satellite autorisé dans Profil");settings();return};satellite=!satellite;loadStyle()}
 private fun drawRoute(r:Route?){if(!ready)return;val src=map?.style?.getSourceAs<GeoJsonSource>("route-data")?:return;val fc=if(r==null)FeatureCollection.fromFeatures(emptyArray<Feature>())else FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(r.points.map{Point.fromLngLat(it.lon,it.lat)})));src.setGeoJson(fc);rendered=r}
 private fun drawPosition(p:Coord){if(ready)map?.style?.getSourceAs<GeoJsonSource>("gps-data")?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(p.lon,p.lat)))}
 private fun recenter(){val l=Session.position;if(l==null){ensureGps();toast("Attendez une position GPS")}else map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(l.latitude,l.longitude),16.5))}
 private fun fresh()=Session.position?.takeIf{it.hasAccuracy()&&it.accuracy<=35&&SystemClock.elapsedRealtimeNanos()-it.elapsedRealtimeNanos<30_000_000_000L}
 private fun testOrs(){if(Session.key.isBlank()){settings();return};if(busy)return;busy=true;val key=Session.key;io.execute{val r=runCatching{Ors.route(key,Coord(45.5019,-73.5674),Coord(45.505,-73.570),alternatives=false).first()};runOnUiThread{busy=false;if(!isDestroyed)AlertDialog.Builder(this).setTitle("Test OpenRouteService").setMessage(r.fold({"Connexion réussie : "+it.meters.roundToInt()+" m"},{"Échec : "+it.message})).setPositiveButton("OK",null).show()}}}
 private fun searchAddress(){if(Session.active||busy)return;val q=search.text.toString().trim();if(q.length<3){toast("Entrez une adresse");return};if(Session.key.isBlank()){settings();return};val key=Session.key;val near=fresh()?.let{Coord(it.latitude,it.longitude)};busy=true;status.text="Recherche…";io.execute{val result=runCatching{Ors.search(key,q,near)};runOnUiThread{busy=false;if(isDestroyed)return@runOnUiThread;result.onSuccess{items->status.text="Choisissez une destination";if(items.isEmpty())toast("Aucun résultat")else AlertDialog.Builder(this).setTitle("Destinations").setItems(items.map{it.first}.toTypedArray()){_,i->confirmDestination(items[i].first,items[i].second)}.show()}.onFailure{status.text="Recherche impossible";toast(it.message?:"Erreur réseau")}}}}
 private fun confirmDestination(label:String,p:Coord){if(busy||Session.active)return;AlertDialog.Builder(this).setTitle(label).setItems(arrayOf("Alternatives recommandées","Rapide","Court")){_,i->plan(p,listOf("recommended","fastest","shortest")[i])}.show()}
 private fun plan(p:Coord,pref:String){val l=fresh();if(l==null){ensureGps();toast("Attendez le GPS précis");return};if(Session.key.isBlank()){settings();return};val key=Session.key;val id=++requestId;busy=true;status.text="Calcul e-bike…";io.execute{val a=Coord(l.latitude,l.longitude);val result=runCatching{try{Ors.route(key,a,p,pref,pref=="recommended")}catch(e:Exception){if(pref!="recommended")throw e;Ors.route(key,a,p,pref,false)}};runOnUiThread{if(isDestroyed||id!=requestId)return@runOnUiThread;busy=false;result.onSuccess{routes->if(routes.isEmpty()){toast("Aucun itinéraire");return@onSuccess};val labels=routes.map{r->"%.1f km • %d min • %s".format(r.meters/1000,(r.seconds/60).roundToInt(),r.ascent?.roundToInt()?.let{"+$it m"}?:"relief inconnu")};AlertDialog.Builder(this).setTitle("Choisir le trajet").setItems(labels.toTypedArray()){_,i->chosen=routes[i];dest=p;Session.preference=pref;Session.route=null;drawRoute(chosen);status.text="Trajet prêt";follow=false;runCatching{map?.animateCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(routes[i].points.map{LatLng(it.lat,it.lon)}).build(),dp(35)))}}.show()}.onFailure{status.text="Calcul impossible";toast(it.message?:"Erreur réseau")}}}}
 private fun startNav(){if(Session.active||busy)return;val r=chosen?:Session.route?:run{toast("Calculez un trajet");return};val d=dest?:Session.destination?:return;if(fresh()==null){ensureGps();toast("GPS précis nécessaire");return};Session.generation++;Session.route=r;Session.destination=d;Session.progress=0.0;Session.remaining=r.meters;Session.active=true;Session.status="Guidage démarré";follow=true;ensureGps();recenter()}
 private fun stopNav(){requestId++;busy=false;Session.generation++;Session.active=false;Session.route=null;Session.destination=null;chosen=null;dest=null;drawRoute(null);Session.status="Navigation arrêtée";status.text=Session.status}
 private fun showMetrics(){val r=Session.route?:chosen;val l=fresh();val speed=if(l?.hasSpeed()==true)l.speed*3.6 else 0.0;val v=prefs.getFloat("voltage",0f).toDouble();val ah=prefs.getFloat("ah",15f).toDouble();val c=prefs.getFloat("charge",100f).toDouble();val w=prefs.getFloat("whkm",16f).toDouble();val reserve=prefs.getFloat("reserve",15f).toDouble();val fraction=if(Session.active)1-Session.progress else 1.0;val km=(if(Session.active)Session.remaining else r?.meters?:0.0)/1000;val e=if(v>0&&r!=null)max(0.0,c-energyWh(km,w,(r.ascent?:0.0)*fraction)/capacityWh(v,ah)*100)else null;val note=if(e==null)"Tension à configurer" else "Charge arrivée estimée "+e.roundToInt()+" %"+if(e<reserve)" • réserve faible" else "";metrics.text="%.1f km/h | %.1f km | %d min estimées\nSamebike %.0f Ah • %s".format(speed,km,((r?.seconds?:0.0)*fraction/60).roundToInt(),ah,note)}
 private fun settings(){val panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),0,dp(16),0)}
 fun field(label:String,value:String,number:Boolean=false,secret:Boolean=false):EditText{panel.addView(text(label,14f));return EditText(this).apply{inputType=if(number)InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL else if(secret)InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT;setText(value);panel.addView(this)}}
 val key=field("Clé OpenRouteService",Session.key,secret=true);val v=field("Tension V (0 = inconnue)",prefs.getFloat("voltage",0f).toString(),true);val ah=field("Capacité Ah",prefs.getFloat("ah",15f).toString(),true);val c=field("Charge initiale %",prefs.getFloat("charge",100f).toString(),true);val w=field("Consommation Wh/km",prefs.getFloat("whkm",16f).toString(),true);val reserve=field("Réserve minimale %",prefs.getFloat("reserve",15f).toString(),true);val sat=field("URL HTTPS satellite autorisée {z}/{x}/{y}",prefs.getString("satellite","").orEmpty());val at=field("Attribution satellite",prefs.getString("attribution","").orEmpty())
 val dialog=AlertDialog.Builder(this).setTitle("Profil Samebike • Samsung S10+").setView(ScrollView(this).apply{addView(panel)}).setNegativeButton("Annuler",null).setPositiveButton("Enregistrer",null).create()
 dialog.setOnShowListener{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{val vv=v.text.toString().toFloatOrNull();val aa=ah.text.toString().toFloatOrNull();val cc=c.text.toString().toFloatOrNull();val ww=w.text.toString().toFloatOrNull();val rr=reserve.text.toString().toFloatOrNull();val ss=sat.text.toString().trim();val attr=at.text.toString().trim();if(vv==null||vv !in 0f..100f||aa==null||aa !in 1f..100f||cc==null||cc !in 0f..100f||ww==null||ww !in 1f..100f||rr==null||rr !in 0f..90f||(ss.isNotEmpty()&&(!ss.startsWith("https://")||attr.isBlank()||listOf("{z}","{x}","{y}").any{!ss.contains(it)}))){toast("Vérifiez les paramètres");return@setOnClickListener};Session.key=key.text.toString().trim();prefs.edit().putString("ors",Session.key).putFloat("voltage",vv).putFloat("ah",aa).putFloat("charge",cc).putFloat("whkm",ww).putFloat("reserve",rr).putString("satellite",ss).putString("attribution",attr).apply();if(satellite){if(ss.isBlank())satellite=false;loadStyle()};dialog.dismiss()}}
 dialog.show()}
 private val tick=object:Runnable{override fun run(){fresh()?.let{l->val p=Coord(l.latitude,l.longitude);drawPosition(p);if(firstFix){firstFix=false;if(follow)recenter()}else if(follow)map?.moveCamera(CameraUpdateFactory.newLatLng(LatLng(p.lat,p.lon)))};if(Session.active){status.text=Session.status;if(rendered!==Session.route)drawRoute(Session.route)}else if(!busy&&(chosen==null||Session.status.contains("arrivé")))status.text=Session.status;showMetrics();ui.postDelayed(this,1000)}}
 override fun onStart(){super.onStart();mapView.onStart();ui.post(tick)}
 override fun onResume(){super.onResume();mapView.onResume()}
 override fun onPause(){mapView.onPause();super.onPause()}
 override fun onStop(){ui.removeCallbacks(tick);mapView.onStop();super.onStop()}
 override fun onLowMemory(){super.onLowMemory();mapView.onLowMemory()}
 override fun onSaveInstanceState(out:Bundle){super.onSaveInstanceState(out);mapView.onSaveInstanceState(out)}
 override fun onDestroy(){ui.removeCallbacks(tick);io.shutdownNow();mapView.onDestroy();super.onDestroy()}
}