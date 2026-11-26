package com.ebike.navigator
import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.location.*
import android.os.*
import android.speech.tts.TextToSpeech
import android.media.AudioAttributes
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.*
object Session{
 @Volatile var position:Location?=null
 @Volatile var route:Route?=null
 @Volatile var destination:Coord?=null
 @Volatile var active=false
 @Volatile var gpsOn=false
 @Volatile var status="En attente du GPS"
 @Volatile var remaining=0.0
 @Volatile var progress=0.0
 @Volatile var key=""
 @Volatile var generation=0
 @Volatile var preference="recommended"
 @Volatile var voice=true
}
class NavigationService:Service(),LocationListener{
 private lateinit var lm:LocationManager
 private var tts:TextToSpeech?=null
 @Volatile private var ready=false
 private val executor=Executors.newSingleThreadExecutor()
 private var lastRoute:Route?=null
 private var lastProgress=0.0
 private var index=0
 private var announced=mutableSetOf<String>()
 private var misses=0
 private var lastReroute=0L
 @Volatile private var rerouting=false
 private var lastSpoken=""
 override fun onBind(i:Intent?)=null
 override fun onCreate(){super.onCreate();lm=getSystemService(LocationManager::class.java);tts=TextToSpeech(this){status->if(status==TextToSpeech.SUCCESS){ready=(tts?.setLanguage(Locale.CANADA_FRENCH)?:-1)>=0;if(!ready)ready=(tts?.setLanguage(Locale.FRANCE)?:-1)>=0;tts?.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())}}}
 override fun onStartCommand(i:Intent?,flags:Int,startId:Int):Int{
 if(i?.action=="REPEAT"){if(Session.active&&lastSpoken.isNotBlank())say(lastSpoken);return START_NOT_STICKY}
 if(i?.action=="STOP"){Session.active=false;Session.generation++;stopSelf();return START_NOT_STICKY}
 val nm=getSystemService(NotificationManager::class.java)
 nm.createNotificationChannel(NotificationChannel("nav","Navigation GPS",NotificationManager.IMPORTANCE_LOW))
 val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
 val stop=PendingIntent.getService(this,1,Intent(this,NavigationService::class.java).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE)
 startForeground(1,Notification.Builder(this,"nav").setSmallIcon(android.R.drawable.ic_menu_directions).setContentTitle("E-Bike Navigator Pro").setContentText("GPS actif").setContentIntent(open).addAction(Notification.Action.Builder(null,"Arrêter",stop).build()).setOngoing(true).build())
 if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){Session.status="Autorisez le GPS précis";stopSelf();return START_NOT_STICKY}
 if(!Session.gpsOn){try{lm.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000L,1f,this);Session.gpsOn=true}catch(e:Exception){Session.status="GPS indisponible";stopSelf()}}
 return START_NOT_STICKY
 }
 private fun say(s:String){lastSpoken=s;Session.status=s;if(ready&&Session.voice)tts?.speak(s,TextToSpeech.QUEUE_FLUSH,null,"nav-"+System.nanoTime())}
 override fun onLocationChanged(l:Location){
 if(!l.hasAccuracy()||l.accuracy>35||SystemClock.elapsedRealtimeNanos()-l.elapsedRealtimeNanos>15_000_000_000L){Session.status="GPS imprécis : vérifiez la carte";return}
 Session.position=l
 if(!Session.active)return
 val r=Session.route?:return
 if(lastRoute!==r){lastRoute=r;lastProgress=0.0;index=0;announced.clear();misses=0}
 val p=Coord(l.latitude,l.longitude)
 val m=matchRoute(p,r,max(0,index-12),min(r.points.lastIndex,index+150))
 val threshold=max(35.0,l.accuracy*2.0)
 if(m.distance>threshold){misses++;if(misses>=3&&!rerouting&&SystemClock.elapsedRealtime()-lastReroute>15000)reroute(p);return}
 misses=0;index=max(index,m.index);lastProgress=max(lastProgress,m.progress)
 Session.progress=(lastProgress/max(1.0,r.cumulative.last())).coerceIn(0.0,1.0)
 Session.remaining=r.meters*(1-Session.progress)
 if(Session.destination?.distance(p)?.let{it<20}==true&&r.cumulative.last()-lastProgress<45){say("Vous êtes arrivé à destination");Session.active=false;Session.remaining=0.0;Session.progress=1.0;return}
 val step=r.steps.firstOrNull{it.index in r.points.indices&&r.cumulative[it.index]>=lastProgress-4&&it.index>0}?:return
 val distance=max(0.0,r.cumulative[step.index]-lastProgress)
 val speed=if(l.hasSpeed())l.speed.toDouble() else 5.0
 val phase=when{distance<=max(12.0,speed*3)->"now";distance<=max(55.0,speed*12)->"near";distance<=max(120.0,speed*25)->"far";else->""}
 Session.status="${distance.toInt()} m · ${step.instruction}"
 if(phase.isNotEmpty()&&announced.add("${step.index}:$phase")){say(if(phase=="now")step.instruction else "Dans ${max(10,(distance/10).roundToInt()*10)} mètres, ${step.instruction}");if(phase=="now"){announced.add("${step.index}:near");announced.add("${step.index}:far")}else if(phase=="near")announced.add("${step.index}:far")}
 }
 private fun reroute(p:Coord){val d=Session.destination?:return;val g=Session.generation;val k=Session.key;val pref=Session.preference;rerouting=true;lastReroute=SystemClock.elapsedRealtime();say("Hors itinéraire. Recalcul en cours.");executor.execute{try{val r=Ors.route(k,p,d,pref,false).first();if(Session.active&&g==Session.generation){Session.route=r;Session.progress=0.0;Session.remaining=r.meters;Session.status="Nouvel itinéraire disponible"}}catch(e:Exception){if(g==Session.generation)say("Recalcul impossible. Arrêtez-vous pour vérifier la carte.")}finally{rerouting=false}}}
 override fun onProviderEnabled(provider:String){}
 override fun onProviderDisabled(provider:String){Session.position=null;Session.status="GPS désactivé"}
 override fun onStatusChanged(provider:String?,status:Int,extras:Bundle?){}
 override fun onDestroy(){lm.removeUpdates(this);tts?.stop();tts?.shutdown();executor.shutdownNow();Session.active=false;Session.gpsOn=false;Session.generation++;Session.position=null;super.onDestroy()}
}