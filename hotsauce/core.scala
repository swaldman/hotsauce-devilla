package hotsauce

import java.util.concurrent.atomic.AtomicLong
import scala.collection.*
import sttp.model.{StatusCode,QueryParams}
import sttp.tapir.ztapir.*
import sttp.tapir.json.jsoniter.*
import sttp.tapir.{EndpointOutput,Schema}
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import zio.*
import zio.http.Server

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

object HotSauceServer extends ZIOAppDefault:

  type ZOut[T] = ZIO[Any,Option[String],T]

  def mapPlainError[U]( task : Task[U] ) : ZOut[U] = task.mapError( t => Some( t.fullStackTrace ) )

  def mapMaybeError[U]( task : Task[Option[U]] ) : ZOut[U] =
    mapPlainError( task ).flatMap:
      case Some( u ) => ZIO.succeed( u )
      case None      => ZIO.fail[Option[String]]( None )

  val either404or500 = oneOf[Option[String]](
    oneOfVariantValueMatcher(statusCode(StatusCode.NotFound).and(stringBody.map(s => None)(_ => "Not Found."))){ case None => true },
    oneOfVariantValueMatcher(statusCode(StatusCode.InternalServerError).and(stringBody.map(s => Some(s))(_.get))){ case Some(_) => true }
  )

  object TapirEndpoint:
    val GetAll      = endpoint.get.in("api").in("hotsauces").in(queryParams).errorOut(either404or500).out(jsonBody[List[HotSauce]])
    val GetCount    = endpoint.get.in("api").in("hotsauces").in("count").errorOut(either404or500).out(jsonBody[Long])
    val GetById     = endpoint.get.in("api").in("hotsauces").in(path[Long]).errorOut(either404or500).out(jsonBody[HotSauce])
    val PostNoId    = endpoint.post.in("api").in("hotsauces").errorOut(either404or500).in(jsonBody[HotSauceData]).out(jsonBody[HotSauce])
    val PostWithId  = endpoint.post.in("api").in("hotsauces").in(path[Long]).errorOut(either404or500).in(jsonBody[HotSauceData]).out(jsonBody[HotSauce])
    val PutById     = endpoint.put.in("api").in("hotsauces").in(path[Long]).errorOut(either404or500).in(jsonBody[HotSauceData]).out(jsonBody[HotSauce])
    val DeleteById  = endpoint.delete.in("api").in("hotsauces").in(path[Long]).errorOut(either404or500).out(jsonBody[HotSauce])
  end TapirEndpoint

  def allFiltered( db : HotSauceDb )( params : QueryParams ) : ZOut[List[HotSauce]] =
    val task = ZIO.attemptBlocking:
      val brandname = params.get("brandname") orElse params.get("brandName")
      val saucename = params.get("saucename") orElse params.get("sauceName")
      val desc      = params.get("desc")      orElse params.get("description")
      val minheat   = params.get("minheat")   orElse params.get("minHeat")
      val maxheat   = params.get("maxheat")   orElse params.get("maxHeat")
      db.findAll()
        .filter( hs => brandname.isEmpty || hs.brandName.contains(brandname.get) )
        .filter( hs => saucename.isEmpty || hs.brandName.contains(saucename.get) )
        .filter( hs => desc.isEmpty      || hs.description.contains(desc.get)    )
        .filter( hs => minheat.isEmpty   || hs.heat >= minheat.get.toInt               )
        .filter( hs => maxheat.isEmpty   || hs.heat <= maxheat.get.toInt               )
        .toList
    mapPlainError( task )

  def count( db : HotSauceDb )(u : Unit) : ZOut[Long] = mapPlainError( ZIO.attemptBlocking( db.count() ) )

  def oneById( db : HotSauceDb )( id : Long ) : ZOut[HotSauce] = mapMaybeError( ZIO.attemptBlocking( db.findById(id) ) )

  def requireField[T]( value : Option[T], fieldName : String ) = require( value.nonEmpty, s"'${fieldName}' field not set, required." )

  def requireAllDataFields( data : HotSauceData ) =
    requireField( data.brandName,   "brandName"   )
    requireField( data.sauceName,   "sauceName"   )
    requireField( data.description, "description" )
    requireField( data.url,         "url"         )
    requireField( data.heat,        "heat"        )

  def createNew( db : HotSauceDb )( data : HotSauceData ) : ZOut[HotSauce] =
    val task = ZIO.attemptBlocking:
      requireAllDataFields( data )
      db.save( data.brandName.get, data.sauceName.get, data.description.get, data.url.get, data.heat.get )
    mapPlainError( task )
    
  def createNewWithId( db : HotSauceDb )( id : Long, data : HotSauceData ) : ZOut[HotSauce] =
    val task = ZIO.attemptBlocking:
      requireAllDataFields(data)
      db.save( id, data.brandName.get, data.sauceName.get, data.description.get, data.url.get, data.heat.get )
    mapPlainError( task )

  def updateById( db : HotSauceDb )( id : Long, data : HotSauceData ) : ZOut[HotSauce] =
    mapPlainError( ZIO.attemptBlocking( db.update( id, data.brandName, data.sauceName, data.description, data.url, data.heat ) ) )

  def deleteById( db : HotSauceDb )( id : Long ) : ZOut[HotSauce] =
    mapMaybeError( ZIO.attemptBlocking( db.deleteById(id) ) )

  def serverEndpoints( db : HotSauceDb ) =
    import TapirEndpoint.*
    List (
      GetAll.zServerLogic( allFiltered(db) ),
      GetCount.zServerLogic( count(db) ),
      GetById.zServerLogic( oneById(db) ),
      PostNoId.zServerLogic( createNew(db) ),
      PostWithId.zServerLogic( createNewWithId(db) ),
      PutById.zServerLogic( updateById(db) ),
      DeleteById.zServerLogic( deleteById(db) ),
    ).map( _.widen[Any] ) // annoying tapir / Scala 3 type inference issue

  override def run =
    for
      db         <- ZIO.succeed( DemoHotSauceDb )
      sEndpoints = serverEndpoints(db)
      httpApp    = ZioHttpInterpreter().toHttp(sEndpoints)
      exitCode   <- Server.serve(httpApp.withDefaultErrorResponse).provide(ZLayer.succeed(Server.Config.default.port(8080)), Server.live).exitCode
    yield exitCode                           

end HotSauceServer

/*
object HotsauceServer extends cask.MainRoutes:

  val db = DemoHotSauceDb

  @cask.get("/api/hotsauces")
  def allHotSauces(brandname : Option[String] = None, saucename : Option[String] = None, desc : Option[String] = None, minheat : Option[Int] = None, maxheat : Option[Int] = None) : cask.Response[String] =
    val result =
      db.findAll()
        .filter( hs => brandname.isEmpty || hs.brandName.contains(brandname.get) )
        .filter( hs => saucename.isEmpty || hs.brandName.contains(saucename.get) )
        .filter( hs => desc.isEmpty      || hs.description.contains(desc.get)    )
        .filter( hs => minheat.isEmpty   || hs.heat >= minheat.get               )
        .filter( hs => maxheat.isEmpty   || hs.heat <= maxheat.get               )
        .toList
    val json = write(result)
    cask.Response(json,200,scala.Seq("Content-Type" -> "application/json"), scala.Seq.empty)

  @cask.get("/api/hotsauces/:next")
  def oneOrCount( next : String ) : cask.Response[String] =
    next match
      case "count" => count()
      case other   => oneHotSauce( other.toLong )

  @cask.postJson("/api/hotsauce")
  def newHotSauce(brandname : String, saucename : String, desc : String, url : String, heat : Int ) : cask.Response[String] =
    val out = db.save( brandname, saucename, desc, url, heat )
    cask.Response(write(out),200,scala.Seq("Content-Type" -> "application/json"), scala.Seq.empty)

  @cask.postJson("/api/hotsauces", subpath = true)
  def newHotSauceWithId( request : cask.Request, brandname : String, saucename : String, desc : String, url : String, heat : Int ) : cask.Response[String] =
    val id = request.remainingPathSegments.head.toLong
    val out = db.save( id, brandname, saucename, desc, url, heat )
    cask.Response(write(out),200,scala.Seq("Content-Type" -> "application/json"), scala.Seq.empty)

  def oneHotSauce( id : Long ) : cask.Response[String] =
    db.findById(id) match
      case Some( hs ) => cask.Response(write(hs),200,scala.Seq("Content-Type" -> "application/json"), scala.Seq.empty)
      case None       => cask.Response(s"Not found: HotSauce with ID ${id}",404,scala.Seq("Content-Type" -> "text/plain"), scala.Seq.empty)

  def count() : cask.Response[String] = cask.Response(write(db.count()),200,scala.Seq("Content-Type" -> "application/json"), scala.Seq.empty)

  initialize()

end HotsauceServer
*/ 

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

object DemoHotSauceDb extends HotSauceDb:
  val WhitespaceRegex = """\s+""".r

  extension (s : String)
    def trimIndentsAndRemoveNewlines() = WhitespaceRegex.replaceAllIn(s.trim(), " ")

  private lazy val sauces = mutable.Map( InitialSauces.map( hs => hs.id -> hs )* )
  private lazy val lastId = new AtomicLong( InitialSauces.map( _.id ).max )

  def count()                 : Long                    = sauces.synchronized( sauces.size )
  def deleteById( id : Long ) : Option[HotSauce]        = sauces.synchronized( sauces.remove(id) )
  def existsById( id : Long ) : Boolean                 = sauces.synchronized( sauces.contains(id) )
  def findById( id : Long )   : Option[HotSauce]        = sauces.synchronized( sauces.get(id) )
  def findAll()               : immutable.Set[HotSauce] = sauces.synchronized( sauces.values.toSet )

  def save( brandName : String, sauceName : String, description : String, url : String, heat : Int ) : HotSauce =
    save( lastId.incrementAndGet, brandName, sauceName, description, url, heat )

  def save( id : Long, brandName : String, sauceName : String, description : String, url : String, heat : Int ) : HotSauce =
    val out = HotSauce( id, brandName, sauceName, description, url, heat )
    sauces.synchronized( sauces += out.id -> out )
    out

  def update( id : Long, brandName : Option[String], sauceName : Option[String], description : Option[String], url : Option[String], heat : Option[Int] ) : HotSauce =
    sauces.synchronized:
      val current = sauces.getOrElse( id, throw new NoSuchElementException(s"Cannot update hot sauce with ID ${id}. No such hot sauce exists.") )
      val updated = HotSauce( id, brandName.getOrElse(current.brandName), sauceName.getOrElse(current.sauceName), description.getOrElse(current.description), url.getOrElse(current.url), heat.getOrElse(current.heat) )
      sauces.put( updated.id, updated )
      updated

  // basically copied from Joey deVilla, https://auth0.com/blog/build-and-secure-an-api-with-spring-boot/
  val InitialSauces = List(
    HotSauce(
      id = 0,
      brandName = "Truff",
      sauceName = "Hot Sauce",
      description = """
                    Our sauce is a curated blend of ripe chili peppers, organic agave nectar, black truffle, and
                    savory spices. This combination of ingredients delivers a flavor profile unprecedented to hot sauce.
                    """.trimIndentsAndRemoveNewlines(),
      url = "https://truffhotsauce.com/collections/sauce/products/truff",
      heat = 2_500
    ),
    HotSauce(
      id = 1,
      brandName = "Truff",
      sauceName = "Hotter Sauce",
      description = """
                    TRUFF Hotter Sauce is a jalapeño rich blend of red chili peppers, Black Truffle and Black Truffle
                    Oil, Organic Agave Nectar, Red Habanero Powder, Organic Cumin and Organic Coriander. Perfectly
                    balanced and loaded with our same iconic flavor, TRUFF Hotter Sauce offers a “less sweet, more heat”
                    rendition of the Flagship original.
                    """.trimIndentsAndRemoveNewlines(),
      url = "https://truffhotsauce.com/collections/sauce/products/hotter-truff-hot-sauce",
      heat = 4_000
    ),
    HotSauce(
      id = 2,
      brandName = "Cholula",
      sauceName = "Original",
      description = """
                    Cholula Original Hot Sauce is created from a generations old recipe that features carefully-selected
                    arbol and piquin peppers and a blend of signature spices. We love it on burgers and chicken but have
                    heard it’s amazing on pizza. Uncap Real Flavor with Cholula Original.
                    """.trimIndentsAndRemoveNewlines(),
      url = "https://www.cholula.com/original.html",
      heat = 3_600
    ),
    HotSauce(
      id = 3,
      brandName = "Mad Dog",
      sauceName = "357",
      description = """
                   Finally, a super hot sauce that tastes like real chile peppers. This sauce is blended
                   with ingredients that create a sauce fit to take your breath away. About five seconds after you
                   taste the recommended dose of one drop, prepare your mouth and mind for five to 20 minutes of agony
                   that all true chileheads fully understand and appreciate.
                   """.trimIndentsAndRemoveNewlines(),
      url = "https://www.saucemania.com.au/mad-dog-357-hot-sauce-148ml/",
      heat = 357_000
    ),
    HotSauce(
      id = 4,
      brandName = "Hot Ones",
      sauceName = "Fiery Chipotle",
      description = """
                    This hot sauce was created with one goal in mind: to get celebrity interviewees on Hot Ones to say
                    "damn that's tasty, and DAMN that's HOT!" and then spill their deepest secrets to host Sean Evans.
                    The tongue tingling flavors of chipotle, pineapple and lime please the palate while the mix of ghost
                    and habanero peppers make this sauce a scorcher. Hot Ones Fiery Chipotle Hot Sauce is a spicy
                    masterpiece.
                    """.trimIndentsAndRemoveNewlines(),
      url = "https://chillychiles.com/products/hot-ones-fiery-chipotle-hot-sauce",
      heat = 15_600
    ),
    HotSauce(
      id = 5,
      brandName = "Hot Ones",
      sauceName = "The Last Dab",
      description = """
                    More than simple mouth burn, Pepper X singes your soul. Starting with a pleasant burn in the mouth,
                    the heat passes quickly, lulling you into a false confidence. You take another bite, enjoying the
                    mustard and spice flavours. This would be great on jerk chicken, or Indian food! But then, WHAM!
                    All of a sudden your skin goes cold and your stomach goes hot, and you realize the power of X.
                    """.trimIndentsAndRemoveNewlines(),
      url = "https://www.saucemania.com.au/hot-ones-the-last-dab-hot-sauce-148ml/",
      heat = 1_000_000
    ),
    HotSauce(
      id = 6,
      brandName = "Torchbearer",
      sauceName = "Zombie Apocalypse",
      description = """
                    The Zombie Apocalypse Hot Sauce lives up to its name, combining Ghost Peppers and Habaneros with a
                    mix of spices, vegetables, and vinegar to create a slow burning blow torch. Some people will feel
                    the heat right away, but others can take a few minutes for the full impact to set in. The heat can
                    last up to 20 minutes, creating a perfect match between very high heat and amazing flavor. Try it
                    on all your favorite foods - wings, chili, soups, steak or even a sandwich in need of a major kick.
                    """.trimIndentsAndRemoveNewlines(),
      url = "https://heatonist.com/products/zombie-apocalypse",
      heat = 100_000
    ),
    HotSauce(
      id = 7,
      brandName = "Heartbeat",
      sauceName = "Pineapple Habanero",
      description = """
                    Pineapple Habanero is Heartbeat Hot Sauce’s most recent offering and their spiciest to date! They’ve
                    yet again collaborated with an Ontario craft brewery, this time from their home town of Thunder Bay.
                    Made with the help of Sleeping Giant Brewery’s award winning Beaver Duck session IPA, this sauce has
                    a boldly pronounced fruitiness and a bright but savoury vibe from start to finish.
                    """.trimIndentsAndRemoveNewlines(),
      url = "https://www.saucemania.com.au/heartbeat-pineapple-habanero-hot-sauce-177ml/",
      heat = 12_200
    ),
    HotSauce(
      id = 8,
      brandName = "Karma Sauce",
      sauceName = "Burn After Eating",
      description = """
                    Karma Sauce Burn After Eating Hot Sauce is imbued with a unique flavour thanks to green mango,
                    ajwain and hing powder. Forged with a top-secret blend of super hots that may or may not include
                    Bhut Jolokia (Ghost), Scorpion, Carolina Reaper, 7-Pot Brown and 7-Pot Primo. This isn’t a sauce you
                    eat, it’s one you survive.
                    """.trimIndentsAndRemoveNewlines(),
      url = "https://www.saucemania.com.au/karma-sauce-burn-after-eating-hot-sauce-148ml/",
      heat = 669_000
    )
  )
end DemoHotSauceDb
