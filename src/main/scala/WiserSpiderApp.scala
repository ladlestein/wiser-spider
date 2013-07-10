package com.nowanswers.wiserspider

import akka.actor._
import akka.pattern._
import com.github.tototoshi.csv.CSVReader
import java.io.{PrintWriter, File}
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
import scala.util.Sorting
import akka.event.Logging
import akka.actor.Status.Failure
import spray.http.HttpResponse
import akka.actor.Status.Success

/**
 * Created with IntelliJ IDEA.
 * User: ladlestein
 * Date: 4/11/13
 * Time: 5:37 PM
 *
 * This fairly ugly code captures data from the Wiser database of non-profits and rates organization
 * based on a particular criteria.
 *
 * I'm still learning how to work with Akka, so it's kind of messy, and there's nary a test!
 */


object WiserSpiderApp extends App {


  val system = ActorSystem("spider")
  val mlog = system.log
  val dispatcher = system.dispatcher

  implicit val ec = ExecutionContext.fromExecutor(dispatcher)
  implicit val timeout = Timeout(2 hours)

  case object StartSpidering
  case object StartReporting
  case object StartEnhancing
  case class QueryIssue(url: String, issueName: String)

  val API_KEY = "5afb532c23893c3a932a38cce6d7b06f"
  val FetchSize = 1000
  val SECRET = "ec8efd4d64c84ab64c099642808393b8"
  val digester = MessageDigest.getInstance("MD5")

  val nApiCallers = 1
  val hostname = "www.wiser.org"
  val ioBridge = IOExtension(system).ioBridge()
  val httpClient = system.actorOf(Props(new HttpClient(ioBridge)))


  val conduit = system.actorOf(
    props = Props(new HttpConduit(httpClient, hostname, 80)),
    name = "http-conduit"
  )
  val pipeline = HttpConduit.sendReceive(conduit)

  if (args.length > 0 && args(0) == "spider") {
    val spider = system.actorOf(Props[Spider], name = "Spider")

    Await.result(spider ? StartSpidering, Duration.Inf)
    println("all results in")
  } else if (args.length > 0 && args(0) == "enhance") {

    val enhancer = system.actorOf(Props[Enhancer], name = "Enhancer")
    Await.result(enhancer ? StartEnhancing, Duration.Inf)

  } else {
    val reporter = system.actorOf(Props[Reporter], name = "Reporter")
    Await.result(reporter ? StartReporting ,Duration.Inf)
    println("see output file")
  }

  System.exit(0)

  class Reporter extends Actor with RealOrganizationStoreComponent with ActorLogging {
    def receive = {
      case StartReporting => {
        val orgs = (datastore loadAll).toArray
        Sorting.quickSort(orgs)
        val writer = new PrintWriter(new File("results.csv"))
        for (org <- orgs.reverse) {
          val s = List(org.name, org.score) ++ org.issues.map(_.name) map ("\"" + _ + "\"")
          writer.println(s.mkString(","))
        }
      }
    }
  }

  class VisitorComponent extends WiserPageVisitorComponent
    with RealWebInterfaceComponent with WiserResultsProcessorComponent with RealOrganizationStoreComponent

  class Enhancer extends Actor with RealOrganizationStoreComponent with ActorLogging {

    val visitors = (for (i <- (1 to nApiCallers)) yield new VisitorComponent) map {
      component => component.visitor }

    val apiCallers = context.actorOf(Props.empty.withRouter(RoundRobinRouter(routees = visitors)), "api-callers")

    def receive = {
      case StartEnhancing => {
        val orgs = datastore loadAll
//        for (org <- orgs) {
//
//        }
      }
    }
  }

  class Spider extends Actor {

    val visitors = (for (i <- (1 to nApiCallers)) yield new VisitorComponent) map { component => component.visitor }
    val apiCallers = context.actorOf(Props.empty.withRouter(RoundRobinRouter(routees = visitors)), "api-callers")

    def receive = {
      case StartSpidering => {
        val originalSender = sender

        try {

          val tasks = Future.sequence(
            for (issue <- Issue.all)
//            for (issue <- issues take 2)
            yield {
              mlog info s"fetching issue ${issue.name}"
              val url = buildSearchUrl(issue.name)
              apiCallers ? QueryIssue(url, issue.name)
            }
          )
          Await.result(tasks, Duration.Inf)
          mlog info "all results in"
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

    def buildSearchUrl(issueName: String, limit: Integer = FetchSize): String = {
      val UrlTemplate = s"/organizations/api_search?phrase=%s&sig=%s&key=$API_KEY&limit=$limit"
      val query = URLEncoder.encode(issueName, "UTF-8")
      val digest = Hex.encodeHexString(
        digester.digest(("phrase" + issueName + "limit" + limit + SECRET).getBytes))
      UrlTemplate.format(query, digest)
    }

  }

  trait WebInterfaceComponent {

    val theWeb: WebInterface

    trait WebInterface {
      def fetchUrl(url: String, originalSender: ActorRef, haveRetried: Boolean = false):  Future[HttpResponse]
    }
  }

  trait RealWebInterfaceComponent extends WebInterfaceComponent {

    lazy val theWeb = new WebInterface {
      def fetchUrl(url: String, originalSender: ActorRef, haveRetried: Boolean): Future[HttpResponse] = {
        mlog debug s"fetching $url"
        pipeline(HttpRequest(method = HttpMethods.GET, uri = url)) map { response =>
          mlog debug s"response.status.value = ${response.status.value}"
          mlog debug s"response.message = ${response.message}"
          response.status.value match {
            case 500 if response.message.toString contains "Per hour call limit reached" => throw CallLimitException(url)
            case _ => response
          }
        } recoverWith {
          case _: CallLimitException => {
            // Just one try, after waiting one hour.
            mlog warning s"call limit reached for $url; waiting one hour"
            if (haveRetried) {
              Thread.sleep((1 hour).toMillis)
              fetchUrl(url, originalSender, haveRetried = true)
            } else {
              mlog error s"retry after one hour failed for $url; this request will go unfilled"
              throw CallLimitException(url)
            }
          }
        }

      }
    }
  }

  trait ResultsProcessorComponent {

    val processor: ResultsProcessor

    trait ResultsProcessor {
       def processResults(text: String, name: String)
    }

  }

  trait WiserResultsProcessorComponent extends ResultsProcessorComponent with OrganizationStoreComponent {
    lazy val processor = new ResultsProcessor {

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
              val org = Organization(id, name, areas flatMap { Issue(_) } toSet)

              datastore save org
              mlog info s"saving $org"
            } else {
              mlog info s"ignoring org with name $name because it doesn't have issue $issueName"
            }
        }

        mlog info s"finished processed ${seq.size} orgs for issue $issueName"
      }

    }
  }

  trait PageVisitorComponent {

    val visitor: ActorRef

  }

  trait WiserPageVisitorComponent
    extends PageVisitorComponent with WebInterfaceComponent with ResultsProcessorComponent {

    lazy val visitor: ActorRef = system.actorOf(Props[PageVisitor])

    class PageVisitor extends Actor {
      def receive = {
        case QueryIssue(url, issueName) => {
          val originalSender = sender

          try {
            val resP = theWeb fetchUrl (url, originalSender)

            resP map {
              res => {
                res.status.isSuccess match {

                  case true => {
                    mlog info s"have body for $url"
                    val text = res.entity.asString

                    processor processResults (text, issueName)
                    originalSender ! Success
                  }

                  case _ => {
                    mlog error s"Got status ${res.status} fetching $url"
                    mlog error s"message: ${res.message}"
                    originalSender ! Failure
                  }

                }
              }
            }
          }
          catch {
            case e: CallLimitException => mlog error "Received call limit exception "
            case e: Exception => {
              mlog error(e, s"Exception thrown in the page visitor for $issueName")
              originalSender ! Failure(e)
              throw e
            }
          }
        }
      }
    }

  }

  trait RealOrganizationStoreComponent extends OrganizationStoreComponent {


    val collection = {
      val connection = MongoConnection()
      val database = connection("spider")
      database ("wiserorgs")
    }

    import com.novus.salat._
    import com.novus.salat.global._

    lazy val datastore: OrganizationStore = new OrganizationStore {

      def save(org: Organization) {
        val dto = OrgDTO(org)
        val mob = grater[OrgDTO].asDBObject(dto)
        mlog info s"inserting ${org.name}"
        collection insert mob
        mlog info s"inserted ${org.name}"
      }

      def loadAll = {
        (collection find) map {
          grater[OrgDTO].asObject(_)
        } map { Organization(_) } toSet
      }

    }

  }


  case class CallLimitException(url: String) extends Exception

}


case class OrgDTO(@Key("_id") id: String, name: String, areas: Set[String])
object OrgDTO {
  def apply(org: Organization): OrgDTO = OrgDTO(org.id, org.name, (org issues) map (_.name) )
}

trait OrganizationStoreComponent {

  val datastore: OrganizationStore

  trait OrganizationStore {
    def save(org: Organization)
    def loadAll: Set[Organization]
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
          val org = Organization(id, name, areas flatMap { Issue(_) } toSet)

          datastore save org
          log info s"saving $org"
        } else {
          log info s"ignoring org with name $name because it doesn't have issue $issueName"
        }
    }

    log info s"finished processed ${seq.size} orgs for issue $issueName"
  }
}



case class Issue(id: Integer, topLevelName: String, name: String, rating: Integer)

case class Organization(id: String, name: String, issues: Set[Issue]) extends Ordered[Organization] {
  def score = issues.foldLeft(0) { (total, issue) => total + issue.rating }

  def compare(that: Organization): Int = {
    score - that.score
  }
}

object Organization {
  def apply(dto: OrgDTO): Organization = {
    Organization(dto.id, dto.name, (dto areas) flatMap { Issue(_)})
  }
}

case class UnknownIssueException(name: String) extends Exception

object Issue {
  val issuesFilePath = "resources/wiser-orgs.csv"
  val reader = CSVReader.open(new File(issuesFilePath))
  val rows = reader.all()

  def apply(name: String) = {
    all.find(_.name == name)
  }

  val all: List[Issue] = rows map { row =>
    val id = row(0).toInt
    val topLevelName = row(1)
    val name = row(2)
    val rating = row(3).toInt

    Issue(id, topLevelName, name, rating)
  }

}