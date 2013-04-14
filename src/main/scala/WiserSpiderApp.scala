package com.nowanswers.wiserspider

import akka.actor.{Props, Actor}
import akka.event.Logging
import akka.pattern._
import com.github.tototoshi.csv.CSVReader
import java.io.File
import java.net.URLEncoder
import com.nowanswers.spider._
import spray.http.{HttpRequest, HttpMethods}
import spray.can.client.HttpClient
import spray.client.HttpConduit
import spray.io.IOExtension
import akka.routing.RoundRobinRouter
import scala.concurrent.{Await, Future}
import akka.actor.Status.Success
import java.security.MessageDigest
import org.apache.commons.codec.binary.Hex
import scala.concurrent.duration.Duration

/**
 * Created with IntelliJ IDEA.
 * User: ladlestein
 * Date: 4/11/13
 * Time: 5:37 PM
 * To change this template use File | Settings | File Templates.
 */
object WiserSpiderApp extends App {

  val API_KEY = "5afb532c23893c3a932a38cce6d7b06f"
  val SECRET = "ec8efd4d64c84ab64c099642808393b8"

  val issuesFilePath = "resources/issues.csv"
  val reader = CSVReader.open(new File(issuesFilePath))
  val rows = reader.all()

  val issues = rows map { row =>
    val id = row(0).toInt
    val topLevelName = row(1)
    val name = row(2)
    val rating = row(3).toInt

    Issue(id, topLevelName, name, rating)
  }

  val nApiCallers = 3
  val hostname = "www.wiser.org"
  val ioBridge = IOExtension(system).ioBridge()
  val httpClient = system.actorOf(Props(new HttpClient(ioBridge)))

  val conduit = system.actorOf(
    props = Props(new HttpConduit(httpClient, hostname, 80)),
    name = "http-conduit"
  )
  val pipeline = HttpConduit.sendReceive(conduit)

  val master = system.actorOf(Props[Master], name = "Master")

  (master ? Start) onComplete { _ =>
    println("all results in, calling System.exit")
    System.exit(0)
  }

  class Master extends Actor {

    val log = Logging(context.system, this)

    val apiCallers = context.actorOf(Props[PageVisitor].withRouter(RoundRobinRouter(nApiCallers)), name = "letterRouter")

    val template = s"/organizations/api_search?phrase=%s&sig=%s&key=$API_KEY"

    val digester = MessageDigest.getInstance("MD5")
    def receive = {
      case Start => {
        val cs = sender
        val tasks = Future.sequence(
          for (issue <- issues take 2)
          yield {
            log info s"fetching issue ${issue.name}"
            val query = URLEncoder.encode(issue.name, "UTF-8")
            val digest = Hex.encodeHexString(digester.digest(("phrase" + issue.name + SECRET).getBytes))
            val url = template.format(query, digest)
            apiCallers ? VisitPage(url)
          }
        )
        Await.result(tasks, Duration.Inf)
        log info "all results in"
      }
    }
  }

  class PageVisitor extends Actor {

    val log = Logging(context.system, this)

    def receive = {
      case VisitPage(url) => {
        val cs = sender
        val resP = pipeline(HttpRequest(method = HttpMethods.GET, uri = url))

        resP map {
          res => {
            res.status.isSuccess
            val body = res.entity.asString
            log info s"have body for $url"

            log info body
            cs ! Success
          }
        }
      }
    }
  }

}



case class Issue(id: Integer, topLevelName: String, name: String, rating: Integer)

// Some code to go through all the issues in Brian's spreadsheet

// Some code to fetch