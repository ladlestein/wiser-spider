package com.nowanswers.wiserspider

import akka.actor.{ActorLogging, ActorSystem, Props, Actor}
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
import scala.concurrent.{ExecutionContext, Await, Future}
import java.security.MessageDigest
import org.apache.commons.codec.binary.Hex
import scala.concurrent.duration.Duration
import org.ccil.cowan.tagsoup.jaxp.SAXFactoryImpl
import akka.util.Timeout
import com.novus.salat.annotations.raw.Key
import com.mongodb.casbah.Imports._
import akka.actor.Status.Failure
import akka.actor.Status.Success

/**
 * Created with IntelliJ IDEA.
 * User: ladlestein
 * Date: 4/11/13
 * Time: 5:37 PM
 * To change this template use File | Settings | File Templates.
 */


object WiserSpiderApp extends App {

  val system = ActorSystem("spider")

  val dispatcher = system.dispatcher

  implicit val ec = ExecutionContext.fromExecutor(dispatcher)

  implicit val timeout = Timeout(1 hour)


  case object Start

  case class QueryIssue(issueName: String)

  val API_KEY = "5afb532c23893c3a932a38cce6d7b06f"
  val SECRET = "ec8efd4d64c84ab64c099642808393b8"
  val digester = MessageDigest.getInstance("MD5")

  val issuesFilePath = "resources/issues.csv"
  val reader = CSVReader.open(new File(issuesFilePath))
  val rows = reader.all()

  val parserFactory = new SAXFactoryImpl

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
        val cs = sender

        try {
          val tasks = Future.sequence(
            for (issue <- issues take 2)
            yield {
              log info s"fetching issue ${issue.name}"
              apiCallers ? QueryIssue(issue.name)
            }
          )
          Await.result(tasks, Duration.Inf)
          log info "all results in"
          cs ! Success
        }
        catch {
          case e: Exception => {
            cs ! Failure(e)
            throw e
          }
        }
      }
    }
  }

  class PageVisitor extends Actor with RealOrganizationStoreComponent with ActorLogging {

    val collection = {
      val connection = MongoConnection()
      val database = connection("spider")
      database ("wiserorgs")
    }

    val UrlTemplate = s"/organizations/api_search?phrase=%s&sig=%s&key=$API_KEY"

    def receive = {
      case QueryIssue(issueName) => {
        val query = URLEncoder.encode(issueName, "UTF-8")
        val digest = Hex.encodeHexString(digester.digest(("phrase" + issueName + SECRET).getBytes))
        val url = UrlTemplate.format(query, digest)

        val cs = sender
        try {
          val resP = pipeline(HttpRequest(method = HttpMethods.GET, uri = url))

          val parser = parserFactory.newSAXParser


          resP map {
            res => {
              res.status.isSuccess match {

                case true => {
                  log info s"have body for $url"

                  val loader = xml.XML.withSAXParser(parser)
                  val doc = loader.loadString(res.entity.asString)
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

                  cs ! Success
                }

                case _ => {
                  log error s"Got status ${res.status} fetching $url"
                  log error s"message: ${res.message}"
                  cs ! Failure
                }

              }
            }
          }
        }
        catch {
          case e: Exception => {
            cs ! Failure(e)
            throw e
          }
        }
      }
    }
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
