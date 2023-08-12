package hotsauce

import java.util.Base64
import scala.io.Source
import scala.util.Using
import sttp.model.{QueryParams, StatusCode}
import sttp.tapir.ztapir.*
import sttp.tapir.json.jsoniter.*
import sttp.tapir.{EndpointOutput, Schema}
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import zio.*
import zio.http.Server

// import pdi.jwt.{Jwt, JwtAlgorithm}
import io.jsonwebtoken.Jwts
import java.security.Key
import java.security.cert.CertificateFactory



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
    val Base          = endpoint.in("api").in("hotsauces").errorOut(either404or500)
    val Authenticated = Base.securityIn( auth.bearer[String]() )

    val GetAll      = Base.get.in(queryParams).out(jsonBody[List[HotSauce]])
    val GetCount    = Base.get.in("count").out(jsonBody[Long])
    val GetById     = Base.get.in(path[Long]).out(jsonBody[HotSauce])
    val PostNoId    = Authenticated.post.in(jsonBody[HotSauceData]).out(jsonBody[HotSauce])
    val PostWithId  = Authenticated.post.in(path[Long]).in(jsonBody[HotSauceData]).out(jsonBody[HotSauce])
    val PutById     = Authenticated.put.in(path[Long]).in(jsonBody[HotSauceData]).out(jsonBody[HotSauce])
    val DeleteById  = Authenticated.delete.in(path[Long]).out(jsonBody[HotSauce])
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

  // this is a placeholder.
  //
  // a real application might put information extracted from the JWT into
  // an object like this to make more fine-grained authentication decisions.
  case class AuthenticationInfo()

  def authenticate( key : Key )( bearerToken : String ) : ZOut[AuthenticationInfo] =
    val task = ZIO.attempt:
      //val decoded = Jwt.decode(bearerToken, secret, Seq(JwtAlgorithm.RS256)).get
      val decoded =
        Jwts.parserBuilder()
          .setSigningKey(key)
          .build()
          .parse(bearerToken)
      println(s"Decoded JWT: ${decoded}")
      AuthenticationInfo() // someday, maybe I'll look into the decoded key and include real information
    mapPlainError(task)

  def createNew( db : HotSauceDb )( auth : AuthenticationInfo )( data : HotSauceData ) : ZOut[HotSauce] =
    val task = ZIO.attemptBlocking:
      requireAllDataFields( data )
      db.save( data.brandName.get, data.sauceName.get, data.description.get, data.url.get, data.heat.get )
    mapPlainError( task )
    
  def createNewWithId( db : HotSauceDb )( auth : AuthenticationInfo )( id : Long, data : HotSauceData ) : ZOut[HotSauce] =
    val task = ZIO.attemptBlocking:
      requireAllDataFields(data)
      db.save( id, data.brandName.get, data.sauceName.get, data.description.get, data.url.get, data.heat.get )
    mapPlainError( task )

  def updateById( db : HotSauceDb )( auth : AuthenticationInfo )( id : Long, data : HotSauceData ) : ZOut[HotSauce] =
    mapPlainError( ZIO.attemptBlocking( db.update( id, data.brandName, data.sauceName, data.description, data.url, data.heat ) ) )

  def deleteById( db : HotSauceDb )( auth : AuthenticationInfo )( id : Long ) : ZOut[HotSauce] =
    mapMaybeError( ZIO.attemptBlocking( db.deleteById(id) ) )

  def serverEndpoints( key : Key, db : HotSauceDb ) : List[ZServerEndpoint[Any,Any]] =
    import TapirEndpoint.*
    List (
      // simple endpoints
      GetAll.zServerLogic( allFiltered(db) ).widen[Any], // annoying tapir / Scala 3 type inference issue
      GetCount.zServerLogic( count(db) ).widen[Any],
      GetById.zServerLogic( oneById(db) ).widen[Any],

      // authenticated endpoints
      PostNoId.zServerSecurityLogic( authenticate(key) ).serverLogic( createNew(db) ),
      PostWithId.zServerSecurityLogic( authenticate(key) ).serverLogic( createNewWithId(db) ),
      PutById.zServerSecurityLogic( authenticate(key) ).serverLogic( updateById(db) ),
      DeleteById.zServerSecurityLogic( authenticate(key) ).serverLogic( deleteById(db) ),
    )

  def keyFromCertificatePemUrl( pemUrl : String ) : Key =
    // see https://stackoverflow.com/questions/6358555/obtaining-public-key-from-certificate
    Using.resource( new java.io.BufferedInputStream( new java.net.URL(pemUrl).openStream ) ): is =>
      val cf = CertificateFactory.getInstance("X.509");
      val certificate = cf.generateCertificate(is)
      certificate.getPublicKey()

  override def run =
    for
      args       <- getArgs
      pemUrl     <- if args.size > 0 then ZIO.succeed(args(0)) else ZIO.fail("No URL provided to pem file of public key by which to decode the JWT token.")
      key        = keyFromCertificatePemUrl(pemUrl)
      db         <- ZIO.succeed( DemoHotSauceDb )
      sEndpoints = serverEndpoints(key, db)
      httpApp    = ZioHttpInterpreter().toHttp(sEndpoints)
      exitCode   <- Server.serve(httpApp.withDefaultErrorResponse).provide(ZLayer.succeed(Server.Config.default.port(8080)), Server.live).exitCode
    yield exitCode                           

end HotSauceServer
