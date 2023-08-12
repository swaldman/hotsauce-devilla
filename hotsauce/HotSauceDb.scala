package hotsauce

import scala.collection.*

trait HotSauceDb:
  def count()                 : Long
  def deleteById( id : Long ) : Option[HotSauce]
  def existsById( id : Long ) : Boolean
  def findById( id : Long )   : Option[HotSauce]
  def findAll()               : immutable.Set[HotSauce]

  def save( brandName : String, sauceName : String, description : String, url : String, heat : Int )            : HotSauce
  def save( id : Long, brandName : String, sauceName : String, description : String, url : String, heat : Int ) : HotSauce

  def update( id : Long, brandName : Option[String], sauceName : Option[String], description : Option[String], url : Option[String], heat : Option[Int] ) : HotSauce
end HotSauceDb  
