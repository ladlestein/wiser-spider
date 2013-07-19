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
import akka.event.{LoggingAdapter, Logging}
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
 * I'm still learning how to work with Akka, so it's kind of messy.
 */

object WiserSpiderApp extends App {

  val command = if (args != null && args.length > 0) { args(0) } else { "spider" };

  val system = ActorSystem("spider")
  val mlog = system.log

  val dispatcher = system.dispatcher

//  implicit val ec = ExecutionContext.fromExecutor(dispatcher)
//
//  trait ProductionContext extends RunContext {
//    def system = WiserSpiderApp.system
//    def mlog = WiserSpiderApp.mlog
//  }

  trait VisitorComponent extends WiserPageVisitorComponent
  with RealWebInterfaceComponent with WiserResultsProcessorComponent with RealOrganizationStoreComponent


  implicit val timeout = Timeout(2 hours)

  val component = command match {
//    case "report" => new ReporterComponent with RealOrganizationStoreComponent with ProductionContext
//    case "enhance" => new EnhancerComponent with RealOrganizationStoreComponent with ProductionContext
//    case "spider" => new SpiderComponent with ProductionContext
    case _ => throw new Exception(s"Unknown command $command")
  }
//  Await.result(component.actor ? Start, Duration.Inf)

  System.exit(0)
}

trait ActorComponent {

  val actor: ActorRef

}

//trait ReporterComponent extends ActorComponent with OrganizationStoreComponent {
//
//  self: RunContext =>
//
//  lazy val actor = system.actorOf(Props(classOf[Reporter]), name = "Reporter")
//
//  trait Reporter extends Actor with ActorLogging {
//
//    def receive = {
//      case Start => {
//        val orgs = (datastore loadAll).toArray
//        Sorting.quickSort(orgs)
//        val writer = new PrintWriter(new File("results.csv"))
//        for (org <- orgs.reverse) {
//          val s = List(org.name, org.score) ++ org.issues.map(_.name) map ("\"" + _ + "\"")
//          writer.println(s.mkString(","))
//        }
//      }
//    }
//
//    val system: ActorSystem = context.system
//    val mlog: LoggingAdapter = log
//
//  }
//
//}
//
//
//trait EnhancerComponent[V <: ActorComponent] extends ActorComponent with OrganizationStoreComponent {
//
//  self: RunContext =>
//
//  lazy val actor = system.actorOf(Props(classOf[Enhancer]), name = "Reporter")
//
//  trait Enhancer[V] extends Actor with ActorLogging {
//
//    val apiCallers = context.actorOf(Props(classOf[V]).withRouter(RoundRobinRouter(N_API_CALLERS)), "api-callers")
//
//    def receive = {
//      case Start => {
//        val orgs = datastore loadAll
//        //        for (org <- orgs) {
//        //
//        //        }
//      }
//    }
//
//  }
//
//}

//trait SpiderComponent extends ActorComponent {
//
//  self: RunContext =>
//
//  lazy val actor = system.actorOf(Props(classOf[Spider]), name = "Spider")
//
//  val visitor: ActorComponent
//
//  trait Spider[V] extends Actor with ActorLogging {
//
//    val DIGESTER = MessageDigest.getInstance("MD5")
//
//    def receive = {
//      case Start => {
//        val originalSender = sender
//
//        try {
//
//          val tasks = Future.sequence(
//            for (issue <- Issue.all)
//            //            for (issue <- issues take 2)
//            yield {
//              log info s"fetching issue ${issue.name}"
//              val url = buildSearchUrl(issue.name)
//              visitor ? QueryIssue(url, issue.name)
//            }
//          )
//          Await.result(tasks, Duration.Inf)
//          log info "all results in"
//          originalSender ! Success
//        }
//        catch {
//          case e: Exception => {
//            originalSender ! Failure(e)
//            throw e
//          }
//        }
//      }
//    }
//
//    def buildSearchUrl(issueName: String, limit: Integer = FETCH_SIZE): String = {
//      val UrlTemplate = s"/organizations/api_search?phrase=%s&sig=%s&key=$API_KEY&limit=$limit"
//      val query = URLEncoder.encode(issueName, "UTF-8")
//      val digest = Hex.encodeHexString(
//        DIGESTER.digest(("phrase" + issueName + "limit" + limit + SECRET).getBytes))
//      UrlTemplate.format(query, digest)
//    }
//
//  }
//}


case class CallLimitException(url: String) extends Exception




case object Start
case class QueryIssue(url: String, issueName: String)


case class OrgDTO(@Key("_id") id: String, name: String, areas: Set[String])
object OrgDTO {
  def apply(org: Organization): OrgDTO = OrgDTO(org.id, org.name, (org issues) map (_.name) )
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

