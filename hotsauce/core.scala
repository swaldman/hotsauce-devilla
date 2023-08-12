package hotsauce

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import sttp.tapir.Schema

case class HotSauce( id : Long, brandName : String, sauceName : String, description : String, url : String, heat : Int )
case class HotSauceData( brandName : Option[String], sauceName : Option[String], description : Option[String], url : Option[String], heat : Option[Int] )

// json codecs
given JsonValueCodec[HotSauce]       = JsonCodecMaker.make
given JsonValueCodec[HotSauceData]   = JsonCodecMaker.make
given JsonValueCodec[List[HotSauce]] = JsonCodecMaker.make // surprised i need this
given JsonValueCodec[Long]           = JsonCodecMaker.make // surprised i need this

given Schema[HotSauce]               = Schema.derived // so tapir can serialize to json
given Schema[HotSauceData]           = Schema.derived // so tapir can serialize to json

extension ( t : Throwable )
  def fullStackTrace : String =
    val sw = new java.io.StringWriter
    val pw = new java.io.PrintWriter(sw)
    t.printStackTrace(pw)
    sw.toString


