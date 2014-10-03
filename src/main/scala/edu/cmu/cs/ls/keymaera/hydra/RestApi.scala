package edu.cmu.cs.ls.keymaera.hydra

import java.io.ByteArrayInputStream

import akka.actor.Actor
import spray.routing._
import spray.http._
import spray.httpx.encoding._
import MediaTypes._
import scala.collection.mutable.HashMap
import spray.json.JsArray

class RestApiActor extends Actor with RestApi {
  def actorRefFactory = context

  //Note: separating the actor and router allows testing of the router without
  //spinning up an actor.
  def receive = runRoute(myRoute)
}

/**
 * RestApi is the API router. See REAMDE.md for a description of the API.
 */
trait RestApi extends HttpService {
  val database = MongoDB //Not sure when or where to create this...

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // Helper Methods
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  private def getUserFromUserIdCookieContent(userIdContent : String):String = userIdContent //for now...

  private def getFileContentsFromFormData(item : BodyPart) : String = {
    val entity = item.entity
    val headers = item.headers
    val content = item.entity.data.asString

    //Just FYI here's how you get the content type...
    val contentType = headers.find(h => h.is("content-type")).get.value

    content
  }

  private def getFileNameFromFormData(item:BodyPart) : String = {
    item.headers.find(h => h.is("content-disposition")).get.value.split("filename=").last
  }

  private def standardCompletion(r : Request) : String = {
    val responses = r.getResultingResponses()
    responses match {
      case hd :: Nil => hd.json.prettyPrint
      case _ => ???
    }
  }


  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // Begin Routing
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  //Some common partials.
  val userPrefix = pathPrefix("user" / Segment)

  //The static directory.
  val staticRoute = pathPrefix("") { get { getFromResourceDirectory("") } }


  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // Users
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  val users = pathPrefix("user" / Segment / Segment) { (username, password) => {
    pathEnd {
      get {
        val request = new LoginRequest(database,username,password)
        complete(request.getResultingResponses().last.json.prettyPrint)
      } ~
      post {
        val request = new CreateUserRequest(database, username, password)
        complete(request.getResultingResponses().last.json.prettyPrint)
      }
    }
  }}

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // Models
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  // FYI to get cookies do this:
  val cookieecho = pathPrefix("cookie_echo") { cookie("userId") { userId => {
    complete(userId.content)
  }}}

  // GET /models/user returns a list of all models belonging to this user. The cookie must be set.
  val modelList = pathPrefix("models" / "users" / Segment) {userId => { pathEnd { get {
    val request = new GetModelListRequest(database, userId)
    val responses = request.getResultingResponses()
    if(responses.length != 1) throw new Exception("Should only have a single response.")
    complete(responses.last.json.prettyPrint)
  }}}}

  //POST /users/<user id>/model/< name >/< keyFile >
  val userModel = userPrefix {userId => {pathPrefix("model" / Segment) {modelNameOrId => {pathEnd {
    post {
      entity(as[MultipartFormData]) { formData => {
        if(formData.fields.length > 1) ??? //should only have a single file.
        val data = formData.fields.last
        val contents = getFileContentsFromFormData(data)
        val request = new CreateModelRequest(database, userId, modelNameOrId, contents)
        complete(standardCompletion(request))
      }}
    } ~
    get {
      val request = new GetModelRequest(database, userId, modelNameOrId)
      complete(standardCompletion(request))
    }
  }}}}}

  //Because apparently FTP > modern web.
  val userModel2 = userPrefix {userId => {pathPrefix("modeltextupload" / Segment) {modelNameOrId =>
  {pathEnd {
    post {
      entity(as[String]) { contents => {
        val request = new CreateModelRequest(database, userId, modelNameOrId, contents)
        complete(standardCompletion(request))
      }}}}}}}}

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // DEPRECATED!
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
//
//
//  /**
//   * POST /proofs/< useridid > with data containing the .key file to load
//   */
//  val createProof = pathPrefix("proofs" / IntNumber) { userid => {
//    pathEnd {
//      post {
//        complete("")
//////        decompressRequest()
////        entity(as[String]) { keyFileContents => {
////          ServerState.createSession(userid.toString)
////          val request = new CreateModelRequest(userid.toString(), "", keyFileContents)
////          val responses = request.getResultingResponses()
////          if(responses.length != 1) {
////            complete(new ErrorResponse(
////              new Exception("CreateProblemRequest generated too many responses"
////             )).json.prettyPrint)
////          }
////          else {
////            complete(responses.last.json.prettyPrint)
////          }
////        }}
//      }
//    }
//  }}
//
//  val getProof = pathPrefix("user" / IntNumber / "proofs" / Segment) { (userid, proofid) =>
//    pathEnd {
//      get {
//        val request = new GetProblemRequest(userid.toString, proofid.toString)
//        val responses = request.getResultingResponses()
//        complete(responses.last.json.prettyPrint)
//      }
//    }
//  }
//
//  /*val runTactic = pathPrefix("user" / IntNumber / "proofs" / Segment / "tactic" / IntNumber) { (userid, proofid, tacticid) => {
//    pathEnd {
//      post {
////        decompressRequest()
//        entity(as[String]) { keyFileContents => {
//          val request = new RunTacticRequest(userid.toString(), tacticid, proofid, "0")
//          val responses = request.getResultingResponses()
//          if(responses.length != 1) {
//            complete(new ErrorResponse(
//              new Exception("CreateProblemRequest generated too many responses"
//             )).json.prettyPrint)
//          }
//          else {
//            complete(responses.last.json.prettyPrint)
//          }
//        }}
//      } ~
//      get {
//        val response = new UnimplementedResponse("GET proofs/<useridid>")
//        complete(response.json.prettyPrint)
//      }
//    }
//  }}*/
//
//  val runTacticNode = pathPrefix("user" / IntNumber / "proofs" / Segment / "node" / Segment / "tactic" / IntNumber) { (userid, proofid, nodeid, tacticid) => {
//    pathEnd {
//      post {
////        decompressRequest()
//        entity(as[String]) { keyFileContents => {
//          val request = new RunTacticRequest(userid.toString(), tacticid, proofid, nodeid)
//          val responses = request.getResultingResponses()
//          if(responses.length != 1) {
//            complete(new ErrorResponse(
//              new Exception("CreateProblemRequest generated too many responses"
//             )).json.prettyPrint)
//          }
//          else {
//            complete(responses.last.json.prettyPrint)
//          }
//        }}
//      } ~
//      get {
//        val response = new UnimplementedResponse("GET proofs/<useridid>")
//        complete(response.json.prettyPrint)
//      }
//    }
//  }}
//
//  val runTacticFormula = pathPrefix("user" / IntNumber / "proofs" / Segment / "node" / Segment / "formula" / Segment / "tactic" / IntNumber) { (userid, proofid, nodeid, formulaid, tacticid) => {
//    pathEnd {
//      post {
////        decompressRequest()
//        entity(as[String]) { keyFileContents => {
//          val request = new RunTacticRequest(userid.toString(), tacticid, proofid, nodeid, Some(formulaid))
//          val responses = request.getResultingResponses()
//          if(responses.length != 1) {
//            complete(new ErrorResponse(
//              new Exception("CreateProblemRequest generated too many responses"
//             )).json.prettyPrint)
//          }
//          else {
//            complete(responses.last.json.prettyPrint)
//          }
//        }}
//      } ~
//      get {
//        val response = new UnimplementedResponse("GET proofs/<useridid>")
//        complete(response.json.prettyPrint)
//      }
//    }
//  }}
//   val getUpdates = path("user" / IntNumber / "getUpdates" / IntNumber) { (userid, count) =>
//     pathEnd {
//      post {
//        val res = new UpdateResponse(ServerState.getUpdates(userid.toString, count)).json.prettyPrint
//        complete(res)
//      }
//    }
//  }
//
  val routes =
    staticRoute::
    users::
    modelList ::
      userModel ::
      userModel2 ::
  cookieecho ::
      //    createProof ::
      //    runTacticNode ::
      //    runTacticFormula ::
      //    getUpdates ::
      //    getProof ::
      //    staticRoute ::
      //    users ::
    Nil
  val myRoute = routes.reduce(_ ~ _)
}
//
//
///// Leaving the implementation of the old api for reference.
//// Note that it's not clear when/if we need respondWithMediaType(`application/json`) ?
////
////  val startSession = path("startSession") {
////    get {
////      val newKey = ServerState.createSession()
////      respondWithMediaType(`application/json`) {
////        complete("{\"sessionName\": \""+newKey+"\"}") //TODO-nrf
////      }
////    }
////  }
////
////  /**
////   * TODO ew. See comment on ServerState.getUpdates...
////   */
////  val getUpdates = path("getUpdates") {
////    get {
////      respondWithMediaType(`application/json`) {
////        parameter("sessionName", "count") {
////           (sessionName, count) => complete(ServerState.getUpdates(sessionName, count))
////        }
////      }
////    }
////  }
////
////  val startNewProblem = path("startNewProblem") {
////    post {
////      parameter("sessionName") { sessionName => {
////        decompressRequest() {
////          entity(as[String]) {
////            problem => {
////              val request = new Problem(sessionName, problem)
////              val result = KeYmaeraClient.serviceRequest(sessionName, request)
////              complete("[]")
////            }
////          }
////        }
////      }}
////    }
////  }
////
////  val formulaToString = path("formulaToString") {
////    get {
////      parameter("sessionName", "uid") { (sessionName,uid) => {
////        val request = new FormulaToStringRequest(sessionName, uid)
////        val result = KeYmaeraClient.serviceRequest(sessionName, request)
////        complete("[" + result.map(_.json).mkString(",") + "]")
////      }}
////    }
////  }
////
////  val formulaToInteractiveString = path("formulaToInteractiveString") {
////    get {
////      parameter("sessionName", "uid") { (sessionName,uid) => {
////        val request = new FormulaToInteractiveStringRequest(sessionName, uid)
////        val result = KeYmaeraClient.serviceRequest(sessionName, request)
////        complete("[" + result.map(_.json).mkString(",") + "]")
////      }}
////    }
////  }
////
////  val formulaFromUid = path("formulaFromUid") {
////    get {
////      parameter("sessionName", "uid") { (sessionName,uid) => {
////        val request = new FormulaFromUidRequest(sessionName, uid)
////        val result = KeYmaeraClient.serviceRequest(sessionName, request)
////        complete("[" + result.map(_.json).mkString(",") + "]")
////      }}
////    }
////  }
////
////  val runTactic = path("runTactic") {
////    get {
////      parameter("sessionName", "tacticName", "uid", "parentId") {
////        (sessionName, tacticName, uid, parentId) => {
////          val request = RunTacticRequest(sessionName, tacticName, uid, None, parentId)
////          val result = KeYmaeraClient.serviceRequest(sessionName, request)
////          complete("[" + result.map(_.json).mkString(",") + "]")
////        }
////      }
////    }
////  }
////
////
////  //TODO-nrf next tactic should be a runTactic that takes some user input. Pass this
////  //input in as a list of strings where the runTactic request passes None.
////
//////  val nodeClosed = path("nodeClosed") undefCall
//////  val nodePruned = path("nodePruned") undefCall
//////  val limitExceeded = path("limitExceeded") undefCall
//////  val startingStrategy = path("startingStrategy") undefCall
//////  val applyTactic = path("applyTactic") undefCall
//////  val getNode = path("getNode") undefCall
////
////  val routes =
////    javascriptRoute ::
////    cssRoute ::
////    staticRoute ::
////    //Real stuff begins here.
////    getUpdates ::
////    startSession ::
////    startNewProblem ::
////    formulaToString ::
////    formulaToInteractiveString ::
////    formulaFromUid::
////    runTactic::
////    Nil
////
////  val myRoute = routes.reduce(_ ~ _)
////}
