package com.ebike.navigator
import kotlin.math.*
import org.json.JSONObject
import org.json.JSONArray
import java.net.URL
import java.net.HttpURLConnection
import java.net.URLEncoder
data class Coord(val lat:Double,val lon:Double){
 fun distance(other:Coord):Double{
 val a=sin(Math.toRadians(other.lat-lat)/2).pow(2)+cos(Math.toRadians(lat))*cos(Math.toRadians(other.lat))*sin(Math.toRadians(other.lon-lon)/2).pow(2)
 return 6371000*2*asin(sqrt(a.coerceIn(0.0,1.0)))
 }
}
data class Step(val instruction:String,val index:Int)
data class Route(val points:List<Coord>,val steps:List<Step>,val meters:Double,val seconds:Double,val ascent:Double?){
 val cumulative=points.fold(mutableListOf<Double>()){acc,p->acc.apply{add(if(isEmpty())0.0 else last()+points[size-1].distance(p))}}
}
data class Match(val index:Int,val distance:Double,val progress:Double)
fun matchRoute(p:Coord,r:Route,from:Int=0,to:Int=r.points.lastIndex):Match{
 var best=Match(0,Double.POSITIVE_INFINITY,0.0)
 for(i in from.coerceIn(0,r.points.lastIndex) until to.coerceIn(0,r.points.lastIndex)){
 val a=r.points[i];val b=r.points[i+1];val k=cos(Math.toRadians(p.lat))
 val x=(b.lon-a.lon)*k;val y=b.lat-a.lat
 val t=if(x*x+y*y==0.0)0.0 else (((p.lon-a.lon)*k*x+(p.lat-a.lat)*y)/(x*x+y*y)).coerceIn(0.0,1.0)
 val d=p.distance(Coord(a.lat+t*(b.lat-a.lat),a.lon+t*(b.lon-a.lon)))
 if(d<best.distance)best=Match(i,d,r.cumulative[i]+t*(r.cumulative[i+1]-r.cumulative[i]))
 }
 return best
}
fun energyWh(km:Double,whPerKm:Double,ascent:Double=0.0,totalMass:Double=100.0):Double=max(0.0,km)*whPerKm+max(0.0,ascent)*totalMass*9.81/3600/0.75
fun capacityWh(voltage:Double,ah:Double)=voltage*ah
fun usableRange(voltage:Double,ah:Double,charge:Double,reserve:Double,whKm:Double)=capacityWh(voltage,ah)*max(0.0,charge-reserve)/100/max(1.0,whKm)
object Ors{
 private fun request(url:String,key:String,body:JSONObject?=null):JSONObject{
 val c=URL(url).openConnection() as HttpURLConnection
 try{
 c.connectTimeout=15000;c.readTimeout=20000;c.instanceFollowRedirects=false;c.setRequestProperty("Accept","application/json, application/geo+json");c.setRequestProperty("Authorization",key)
 if(body!=null){c.requestMethod="POST";c.doOutput=true;c.setRequestProperty("Content-Type","application/json");c.outputStream.use{it.write(body.toString().toByteArray())}}
 val status=c.responseCode
 if(status !in 200..299)throw IllegalStateException(when(status){401,403->"ORS : clé refusée ou non autorisée";429->"ORS : quota atteint";else->"ORS : HTTP "+status})
 return JSONObject(c.inputStream.bufferedReader().use{it.readText()})
 }finally{c.disconnect()}
 }
 fun search(key:String,text:String,near:Coord?):List<Pair<String,Coord>>{
 val q=URLEncoder.encode(text,"UTF-8")
 val focus=near?.let{"&focus.point.lat=${it.lat}&focus.point.lon=${it.lon}"}?:""
 val j=request("https://api.openrouteservice.org/geocode/search?text=$q&size=6$focus",key).getJSONArray("features")
 return (0 until j.length()).map{i->val f=j.getJSONObject(i);val a=f.getJSONObject("geometry").getJSONArray("coordinates");f.getJSONObject("properties").getString("label") to Coord(a.getDouble(1),a.getDouble(0))}
 }
 fun route(key:String,a:Coord,b:Coord,preference:String="recommended",alternatives:Boolean=true):List<Route>{
 val body=JSONObject().put("coordinates",JSONArray().put(JSONArray().put(a.lon).put(a.lat)).put(JSONArray().put(b.lon).put(b.lat))).put("language","fr").put("instructions",true).put("elevation",true).put("preference",preference)
 if(alternatives)body.put("alternative_routes",JSONObject().put("target_count",3).put("share_factor",0.6).put("weight_factor",1.6))
 val j=request("https://api.openrouteservice.org/v2/directions/cycling-electric/geojson",key,body).getJSONArray("features")
 return (0 until j.length()).map{i->
 val f=j.getJSONObject(i);val arr=f.getJSONObject("geometry").getJSONArray("coordinates");val p=f.getJSONObject("properties");val s=p.getJSONObject("summary");val seg=p.getJSONArray("segments");val steps=mutableListOf<Step>()
 for(k in 0 until seg.length()){val ss=seg.getJSONObject(k).getJSONArray("steps");for(m in 0 until ss.length()){val x=ss.getJSONObject(m);steps.add(Step(x.getString("instruction"),x.getJSONArray("way_points").getInt(0)))}}
 val points=(0 until arr.length()).map{val c=arr.getJSONArray(it);Coord(c.getDouble(1),c.getDouble(0))}
 val climb=(1 until arr.length()).takeIf{arr.length()>1 && arr.getJSONArray(0).length()>2}?.sumOf{max(0.0,arr.getJSONArray(it).optDouble(2,0.0)-arr.getJSONArray(it-1).optDouble(2,0.0))}
 Route(points,steps,s.getDouble("distance"),s.getDouble("duration"),climb)
 }.filter{it.points.size>=2}
 }
}