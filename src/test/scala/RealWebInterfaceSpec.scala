package com.nowanswers.wiserspider

import org.specs2.mutable.Specification
import org.specs2.specification.Scope
import concurrent._
import concurrent.duration._
import org.specs2.time.NoTimeConversions
import akka.util.Timeout
import akka.actor.ActorSystem
import spray.client.pipelining
import org.specs2.mock.Mockito
import spray.http.{HttpEntity, HttpResponse, HttpRequest}
import ExecutionContext.Implicits.global


/**
 * Created with IntelliJ IDEA.
 * User: ladlestein
 * Date: 7/19/13
 * Time: 12:08 AM
 * To change this template use File | Settings | File Templates.
 */
class RealWebInterfaceSpec extends Specification with NoTimeConversions with Mockito {

  "The real web interface" should {

    val duration = (5 seconds)

    "fetch a page" in new RealWebInterfaceComponent with TestContext with Scope {
      import spray.client.pipelining._

      val pipeline = sendReceive
      val waitPeriod = (1 hour)

      Await.result(theWeb.fetchUrl("http://www.w3c.org/", null, false), duration).status.isSuccess must_== true

    }

    val limitResponse = future {
      HttpResponse(status = 500, entity = HttpEntity("Per hour call limit reached"))
    }
    val successResponse = future {
      HttpResponse(entity = HttpEntity("stuff"))
    }

    "retry when the API limit is it" in new RealWebInterfaceComponent with TestContext with Scope {
      val pipeline = mock[pipelining.SendReceive]
      val waitPeriod = 3 seconds

      pipeline(any[HttpRequest]) returns limitResponse thenReturns successResponse
      Await.result(theWeb.fetchUrl("http://anyoldhost/", null, false), duration).entity.asString must_== "stuff"
    }

    "fail when the API limit is it on a retry" in new RealWebInterfaceComponent with TestContext with Scope {
      val pipeline: (HttpRequest) => Future[HttpResponse] = mock[pipelining.SendReceive]
      val waitPeriod: FiniteDuration = 4 seconds

      pipeline(any[HttpRequest]) returns limitResponse thenReturns limitResponse
      Await.result(theWeb.fetchUrl("http://anyoldhost/", null, false), duration) must throwA[CallLimitException]
    }
  }

}

