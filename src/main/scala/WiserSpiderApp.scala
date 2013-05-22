package com.nowanswers.wiserspider

import akka.actor._
import akka.event.Logging
import akka.pattern._
import com.github.tototoshi.csv.CSVReader
import java.io.File
import java.net.URLEncoder
import scala.concurrent.duration._
import spray.http.{HttpRequest, HttpMethods}
import spray.can.client.HttpClient
import spray.client.HttpConduit
import spray.io.IOExtension
import akka.routing.RoundRobinRouter
import scala.concurrent._
import java.security.MessageDigest
import org.apache.commons.codec.binary.Hex
import scala.concurrent.duration.Duration
import org.ccil.cowan.tagsoup.jaxp.SAXFactoryImpl
import akka.util.Timeout
import com.novus.salat.annotations.raw.Key
import com.mongodb.casbah.Imports._
import akka.actor.Status.Failure
import spray.http.HttpResponse
import akka.actor.Status.Success

/**
 * Created with IntelliJ IDEA.
 * User: ladlestein
 * Date: 4/11/13
 * Time: 5:37 PM
 * To change this template use File | Settings | File Templates.
 */


object WiserSpider extends App {

  val system = ActorSystem("spider")
  val log = system.log
  val dispatcher = system.dispatcher

  implicit val ec = ExecutionContext.fromExecutor(dispatcher)
  implicit val timeout = Timeout(2 hours)

  case object Start
  case class QueryIssue(issueName: String)

  val API_KEY = "5afb532c23893c3a932a38cce6d7b06f"
  val FetchSize = 1000
  val SECRET = "ec8efd4d64c84ab64c099642808393b8"
  val digester = MessageDigest.getInstance("MD5")

  val issuesFilePath = "resources/wiser-orgs.csv"
  val reader = CSVReader.open(new File(issuesFilePath))
  val rows = reader.all()

  val issues = rows map { row =>
    val id = row(0).toInt
    val topLevelName = row(1)
    val name = row(2)
    val rating = row(3).toInt

    Issue(id, topLevelName, name, rating)
  }

  val nApiCallers = 1
  val hostname = "www.wiser.org"
  val ioBridge = IOExtension(system).ioBridge()
  val httpClient = system.actorOf(Props(new HttpClient(ioBridge)))


  val conduit = system.actorOf(
    props = Props(new HttpConduit(httpClient, hostname, 80)),
    name = "http-conduit"
  )
  val pipeline = HttpConduit.sendReceive(conduit)

  val master = system.actorOf(Props[Master], name = "Master")

  Await.result(master ? Start, Duration.Inf)
  println("all results in, calling System.exit")
  System.exit(0)

  class Master extends Actor {

    val log = Logging(context.system, this)

    val apiCallers = context.actorOf(Props[PageVisitor].withRouter(RoundRobinRouter(nApiCallers)), name = "letterRouter")

    def receive = {
      case Start => {
        val originalSender = sender

        try {

          val tasks = Future.sequence(
            for (issue <- issues)
//            for (issue <- issues take 2)
            yield {
              log info s"fetching issue ${issue.name}"
              apiCallers ? QueryIssue(issue.name)
            }
          )
          Await.result(tasks, Duration.Inf)
          log info "all results in"
          originalSender ! Success
        }
        catch {
          case e: Exception => {
            originalSender ! Failure(e)
            throw e
          }
        }
      }
    }
  }

  class PageVisitor extends Actor with RealOrganizationStoreComponent with WiserResultProcessor with ActorLogging {

    val collection = {
      val connection = MongoConnection()
      val database = connection("spider")
      database ("wiserorgs")
    }

    def receive = {
      case QueryIssue(issueName) => {
        val originalSender = sender

        try {
          val url = buildSearchUrl(issueName)
          val resP = fetchUrl(url, originalSender)

          resP map {
            res => {
              res.status.isSuccess match {

                case true => {
                  log info s"have body for $url"
                  val text = res.entity.asString

                  processResults(text, issueName)
                  originalSender ! Success
                }

                case _ => {
                  log error s"Got status ${res.status} fetching $url"
                  log error s"message: ${res.message}"
                  originalSender ! Failure
                }

              }
            }
          }
        }
        catch {
          case e: CallLimitException => log error "Received call limit exception "
          case e: Exception => {
            log error(e, s"Exception thrown in the page visitor for $issueName")
            originalSender ! Failure(e)
            throw e
          }
        }
      }
    }
  }

  case class CallLimitException(url: String) extends Exception

  def fetchUrl(url: String, originalSender: ActorRef, haveRetried: Boolean = false): Future[HttpResponse] = {
    log debug s"fetching $url"
    pipeline(HttpRequest(method = HttpMethods.GET, uri = url)) map { response =>
      log debug s"response.status.value = ${response.status.value}"
      log debug s"response.message = ${response.message}"
      response.status.value match {
        case 500 if response.message.toString contains "Per hour call limit reached" => throw CallLimitException(url)
        case _ => response
      }
    } recoverWith {
      case _: CallLimitException => {
        // Just one try, after waiting one hour.
        log warning s"call limit reached for $url; waiting one hour"
        if (haveRetried) {
          Thread.sleep((1 hour).toMillis)
          fetchUrl(url, originalSender, true)
        } else {
          log error s"retry after one hour failed for $url; this request will go unfilled"
          throw CallLimitException(url)
        }
      }
    }
  }

  def buildSearchUrl(issueName: String, limit: Integer = FetchSize): String = {
    val UrlTemplate = s"/organizations/api_search?phrase=%s&sig=%s&key=$API_KEY&limit=$limit"
    val query = URLEncoder.encode(issueName, "UTF-8")
    val digest = Hex.encodeHexString(
      digester.digest(("phrase" + issueName + "limit" + limit + SECRET).getBytes))
    UrlTemplate.format(query, digest)
  }
}


case class OrgDTO(@Key("_id") id: String, name: String, areas: Set[String])
object OrgDTO {
  def apply(org: Organization): OrgDTO = OrgDTO(org.id, org.name, org.issues )
}

trait OrganizationStoreComponent {

  val datastore: OrganizationStore

  trait OrganizationStore {
     def save(org: Organization)
  }
}

trait WiserResultProcessor {
  self: OrganizationStoreComponent with ActorLogging =>

  val parserFactory = new SAXFactoryImpl

  def processResults(text: String, issueName: String) {
    val parser = parserFactory.newSAXParser
    val loader = xml.XML.withSAXParser(parser)
    val doc = loader.loadString(text)
    val seq = doc \\ "organization"

    seq foreach {
      orgNode =>
        val name = (orgNode \ "@name") text
        val id = (orgNode \ "@id") text
        val areas = ((orgNode \ "@area") text) split ","

        if (areas contains issueName) {
          val org = Organization(id, name, areas toSet)

          datastore save org
          log info s"saving $org"
        } else {
          log info s"ignoring org with name $name because it doesn't have issue $issueName"
        }
    }

    log info s"finished processed ${seq.size} orgs for issue $issueName"
  }
}

trait RealOrganizationStoreComponent extends OrganizationStoreComponent {

  self: ActorLogging =>

  import com.novus.salat._
  import com.novus.salat.global._
  import com.mongodb.casbah.MongoCollection

  def collection: MongoCollection

  lazy val datastore: OrganizationStore = new OrganizationStore{
    def save(org: Organization) {
      val dto = OrgDTO(org)
      val mob = grater[OrgDTO].asDBObject(dto)
      log info s"inserting ${org.name}"
      collection insert mob
      log info s"inserted ${org.name}"
    }
  }
}



case class Issue(id: Integer, topLevelName: String, name: String, rating: Integer)
case class Organization(id: String, name: String, issues: Set[String])
