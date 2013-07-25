package com.nowanswers.wiserspider

import org.specs2.mutable._
import org.specs2.specification.Scope
import org.specs2.mock.Mockito
import akka.util.Timeout
import akka.actor.{Status, Actor, ActorRef, ActorSystem}
import akka.pattern._
import concurrent._
import concurrent.duration._
import spray.http.{HttpEntity, HttpResponse, HttpBody}
import akka.testkit.{ImplicitSender, TestKit, TestActor}


/**
 * Created with IntelliJ IDEA.
 * User: ladlestein
 * Date: 7/9/13
 * Time: 5:05 PM
 * To change this template use File | Settings | File Templates.
 */


trait TestContext extends RunContext {
  implicit val system = ActorSystem("wiser-spider-test")   // TODO I think this can be made implicit.
  val mlog = system.log
}

class WiserPageVisitorSpec
  extends Specification with WiserPageVisitorComponent with Mockito with org.specs2.time.NoTimeConversions with TestContext {

  isolated

  import ExecutionContext.Implicits.global

  implicit val timeout = Timeout(2 hours)

  val theWeb = mock[WebInterface]
  val processor = mock[ResultsProcessor]

  val url = "http://something"
  val body = "stuff"
  val issueName = "things"

  "The wiser page visitor" should {
    "passes a result along" in new Scope {

      theWeb.fetchUrl(be_===(url), any[ActorRef] , any[Boolean]) returns future { HttpResponse(entity = HttpEntity(body)) }
      val result = actor ? QueryIssue(url, issueName)
      Await.result(result, 2 seconds)
      there was one(processor).processResults(body, issueName)
    }

    "reports a success" in new TestKit(system) with ImplicitSender with Scope {
      theWeb.fetchUrl(be_===(url), any[ActorRef] , any[Boolean]) returns future { HttpResponse(entity = HttpEntity(body)) }
      actor ! QueryIssue(url, issueName)
      expectMsg(Status.Success)
    }
    "reports a failure" in new TestKit(system) with ImplicitSender with Scope {

      theWeb.fetchUrl(be_===(url), any[ActorRef] , any[Boolean]) returns future { HttpResponse(status = 500, entity = HttpEntity(body)) }
      actor ! QueryIssue(url, issueName)
      expectMsg(Status.Failure)
    }
  }

  val nActors: Integer = 1

}

