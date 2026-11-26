package com.ebike.navigator
import org.junit.Test
import org.junit.Assert.*
class CoreTest{
 @Test fun battery(){assertEquals(720.0,capacityWh(48.0,15.0),0.001);assertEquals(160.0,energyWh(10.0,16.0),0.001)}
 @Test fun reserve(){assertEquals(38.25,usableRange(48.0,15.0,100.0,15.0,16.0),0.001);assertEquals(0.0,usableRange(48.0,15.0,10.0,15.0,16.0),0.001)}
 @Test fun distance(){assertEquals(111.2,Coord(0.0,0.0).distance(Coord(0.001,0.0)),0.5)}
 @Test fun projection(){val r=Route(listOf(Coord(0.0,0.0),Coord(0.0,0.01)),emptyList(),1112.0,200.0,null);val m=matchRoute(Coord(0.001,0.005),r);assertEquals(111.2,m.distance,0.5);assertEquals(556.0,m.progress,1.0)}
}