package com.nowanswers.wiserspider

import akka.actor._
import scala.concurrent.{ExecutionContext, Future}
import org.ccil.cowan.tagsoup.jaxp.SAXFactoryImpl
import akka.actor.Status.{Success, Failure}
import com.mongodb.casbah.Imports._
import spray.http._
import spray.client.pipelining._
import akka.event.LoggingAdapter
import scala.concurrent.duration._
import ExecutionContext.Implicits.global


trait RunContext {
  implicit def system: ActorSystem
  def mlog: LoggingAdapter
}

trait WebInterfaceComponent {

  val theWeb: WebInterface

  trait WebInterface {
    def fetchUrl(url: String, originalSender: ActorRef, haveRetried: Boolean = false):  Future[HttpResponse]
  }
}

trait RealWebInterfaceComponent extends WebInterfaceComponent {

  self: RunContext =>


  lazy val pipeline = sendReceive

  lazy val theWeb = new WebInterface {
    def fetchUrl(url: String, originalSender: ActorRef, haveRetried: Boolean): Future[HttpResponse] = {
      mlog debug s"fetching $url"
      pipeline(HttpRequest(method = HttpMethods.GET, uri = url)) map { response =>
        mlog debug s"response.status.value = ${response.status.value}"
        mlog debug s"response.message = ${response.message}"
        response.status.value match {
          case "500" if response.message.toString contains "Per hour call limit reached" => throw CallLimitException(url)
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
    def processResults(text: String, name: String): AnyRef
  }

}

trait WiserResultsProcessorComponent extends ResultsProcessorComponent with OrganizationStoreComponent with RunContext {
  lazy val processor = new ResultsProcessor {

    val parserFactory = new SAXFactoryImpl

    def processResults(text: String, issueName: String): AnyRef = {
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
      return "32"
    }

  }
}

trait WiserPageVisitorComponent
  extends ActorComponent with WebInterfaceComponent with ResultsProcessorComponent {

  self: RunContext =>

  lazy val actor: ActorRef = system.actorOf(Props(classOf[PageVisitor], this), "api-callers")

  val nActors: Integer

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
                  mlog error s"original sender: ${originalSender}"
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

trait RealOrganizationStoreComponent extends OrganizationStoreComponent with RunContext {


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

