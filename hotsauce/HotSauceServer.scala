package hotsauce

import sttp.model.{StatusCode,QueryParams}
import sttp.tapir.ztapir.*
import sttp.tapir.json.jsoniter.*
import sttp.tapir.{EndpointOutput,Schema}
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import zio.*
import zio.http.Server

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
